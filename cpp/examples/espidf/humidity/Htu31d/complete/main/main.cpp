#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "Htu31d.h"

#define I2C_SDA  21
#define I2C_SCL  22
#define HTU31D_ADDR 0x40

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
    dev_cfg.device_address = HTU31D_ADDR;
    dev_cfg.scl_speed_hz = 100000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    HTU31DFull sensor(connection);                                  // Create HTU31D driver, (connection) → void
    sensor.init();                                                  // Soft reset, () → void
                                                                    // 0x1E, waits 15 ms; highest resolution, heater off
    float t = 0, rh = 0;
    uint16_t t_raw = 0, rh_raw = 0;
    uint8_t osr_rh = 0, osr_t = 0;
    bool ok = sensor.read(t, rh);                                   // Read temperature and humidity, (temperature_c&, humidity_pct&) → bool
                                                                    // OSR 3 / 3 by default, both CRCs verified
    printf("temperature %.2f C humidity %.1f %%RH\n", (double)(t), (double)(rh));
    printf("humidity %.1f %%RH\n", (double)(sensor.read_humidity()));// Read humidity only, () → float %RH
                                                                    // command 0x10; NAN on failure
    printf("temperature %.2f C\n", (double)(sensor.read_temperature()));// Read temperature only, () → float °C
                                                                    // NAN on failure
    ok = sensor.read_raw(t_raw, rh_raw);                            // Read raw codes, (t_raw&, rh_raw&) → bool
                                                                    // 16-bit codes
    printf("raw T %lu raw RH %lu\n", (unsigned long)(t_raw), (unsigned long)(rh_raw));
    ok = sensor.set_resolution(1, 2);                               // Set oversampling, (osr_rh, osr_t) → bool
                                                                    // 0–3 each; higher OSR = finer resolution, longer conversion
    sensor.get_resolution(osr_rh, osr_t);                           // Get oversampling, (osr_rh&, osr_t&) → void
    printf("osr rh %lu osr t %lu\n", (unsigned long)(osr_rh), (unsigned long)(osr_t));
    printf("conversion %.1f ms\n", (double)(sensor.conversion_time() * 1000.0f));// Typical conversion time, () → float s
    sensor.set_resolution(3, 3);
    sensor.start_conversion();                                      // Start a conversion, () → void
                                                                    // non-blocking; wait conversion_time() before reading
    vTaskDelay(pdMS_TO_TICKS(30));
    ok = sensor.read_result(t, rh);                                 // Read last conversion result, (temperature_c&, humidity_pct&) → bool
    printf("result %.2f C %.1f %%RH\n", (double)(t), (double)(rh));
    sensor.set_heater(true);                                        // Switch the heater on, (on) → void
                                                                    // 0x04; readings drift while the heater runs
    printf("heater on %lu\n", (unsigned long)(sensor.is_heater_on()));// Heater state, () → bool
    sensor.set_heater(false);                                       // Heater off
    printf("diagnostic 0x%02X\n", (unsigned)(sensor.diagnostic())); // Read the diagnostic byte, () → uint8_t
                                                                    // test against HTU31DFull::DIAG_* masks
    uint32_t serial = 0;
    ok = sensor.serial_number(serial);                              // Read the serial number, (serial&) → bool
                                                                    // 24-bit, CRC verified
    printf("serial 0x%02X\n", (unsigned)(serial));
    printf("dew point %.2f C\n", (double)(sensor.dew_point()));     // Compute dew point, () → float °C
                                                                    // one measurement, Magnus formula; NAN on failure
    sensor.software_reset();                                        // Soft reset, () → void
                                                                    // 0x1E, waits 15 ms; heater off, OSR 3 / 3
    printf("ok %lu\n", (unsigned long)(ok));
}
