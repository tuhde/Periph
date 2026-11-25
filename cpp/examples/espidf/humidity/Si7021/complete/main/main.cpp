#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "Si7021.h"

#define I2C_SDA  21
#define I2C_SCL  22
#define AHT_ADDR 0x40

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {};
    bus_cfg.i2c_port = I2C_NUM_0;
    bus_cfg.sda_io_num = static_cast<gpio_num_t>(I2C_SDA);
    bus_cfg.scl_io_num = static_cast<gpio_num_t>(I2C_SCL);
    bus_cfg.clk_source = I2C_CLK_SRC_DEFAULT;
    bus_cfg.glitch_ignore_cnt = 7;
    bus_cfg.flags.enable_internal_pullup = true;
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {};
    dev_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev_cfg.device_address = AHT_ADDR;
    dev_cfg.scl_speed_hz = 100000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    Si7021Full sensor(connection);                                      // Create Si7021 driver, (connection) → void
    float t = 0, rh = 0;
    uint16_t raw = 0;
    uint8_t rh_bits = 0, t_bits = 0;

    sensor.read(t, rh);                                                 // Read temperature and humidity, (t, rh) → bool °C, %RH
                                                                        // RH without hold master, temperature of the same conversion
    printf("%.2f C, %.1f %%RH\n", t, rh);
    printf("humidity %.1f %%RH\n", sensor.read_humidity());
    // sensor.read_humidity() → float %RH (NAN on failure)
    printf("temperature %.2f C\n", sensor.read_temperature());
    // sensor.read_temperature() → float °C, new conversion (NAN on failure)
    printf("temperature of last RH measurement %.2f C\n", sensor.read_temperature_previous());
    // sensor.read_temperature_previous() → float °C, command 0xE0, no new conversion (NAN on failure)
    sensor.read_raw_humidity(raw);                                      // Read raw RH code, (code) → bool
                                                                        // 16-bit code, status bits included
    printf("raw RH %u\n", (unsigned)raw);
    sensor.read_raw_temperature(raw);                                   // Read raw temperature code, (code) → bool
    printf("raw T %u\n", (unsigned)raw);

    sensor.set_resolution(11, 11);                                      // Set resolution, (rh_bits, temperature_bits) → bool
                                                                        // (12, 14) / (8, 12) / (10, 13) / (11, 11)
    sensor.get_resolution(rh_bits, t_bits);                             // Get resolution, (rh_bits, t_bits) → void
    printf("resolution %u / %u bit\n", (unsigned)rh_bits, (unsigned)t_bits);
    printf("conversion time %.1f ms\n", sensor.conversion_time() * 1000.0f);
    // sensor.conversion_time() → float s, typical RH + temperature time
    sensor.set_resolution(12, 14);

    sensor.set_heater(true);                                            // Switch the heater, (on) → void
                                                                        // about 3.1 mA; readings drift while on
    printf("heater on %u\n", (unsigned)sensor.is_heater_on());
    // sensor.is_heater_on() → bool
    sensor.set_heater(false);
    printf("VDD low %u\n", (unsigned)sensor.vdd_low());
    // sensor.vdd_low() → bool, true when VDD is below about 1.9 V
    uint8_t reg = sensor.read_user_register();                          // Read USER_REG_1, () → uint8_t
                                                                        // 0x3A after reset
    sensor.write_user_register(reg);                                    // Write USER_REG_1, (value) → void
                                                                        // reserved bits are preserved
    sensor.set_clock_stretching(false);                                 // Use hold-master commands, (on) → void
                                                                        // needs bus-layer support; off polls the NACKed read header

    uint64_t sn = sensor.serial_number();                               // Electronic serial number, () → uint64_t
    printf("serial number 0x%08lX%08lX\n", (unsigned long)(sn >> 32), (unsigned long)(sn & 0xFFFFFFFFUL));
    printf("device type 0x%02X\n", (unsigned)sensor.device_type());
    // sensor.device_type() → uint8_t, 0x15 = Si7021
    printf("firmware 0x%02X\n", (unsigned)sensor.firmware_revision());
    // sensor.firmware_revision() → uint8_t, 0xFF = firmware 1.0
    printf("dew point %.2f C\n", sensor.dew_point());
    // sensor.dew_point() → float °C, one measurement, Magnus formula (NAN on failure)
    sensor.software_reset();                                            // Software reset, () → void
                                                                        // 0xFE, waits 15 ms
    printf("done\n");
    while (true) vTaskDelay(pdMS_TO_TICKS(1000));
}
