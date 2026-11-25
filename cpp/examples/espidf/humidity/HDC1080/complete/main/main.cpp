#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "HDC1080.h"

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
    HDC1080Full sensor(connection);                                     // Create HDC1080 driver, (connection) → void
    float t = 0, rh = 0;
    uint16_t raw_t = 0, raw_rh = 0;

    uint64_t serial = 0; uint16_t cfg = 0;
    sensor.init();                                                      // Write default configuration, () → void
                                                                        // 0x1000: 14-bit T and RH, sequence mode, heater off
    serial = sensor.serial_id();                                        // Read the 40-bit serial ID, () → uint64_t
    printf("serial id 0x%02X%08X\n", (unsigned)((serial) >> 32), (unsigned)((serial) & 0xFFFFFFFF));
    cfg = sensor.read_config();                                         // Read the configuration register, () → uint16_t
    printf("config 0x%04X\n", (unsigned)(cfg));
    sensor.read(t, rh);                                                 // Read temperature and humidity, (t, rh) → bool °C, %RH
                                                                        // sequence mode, polls the NACKed read header (timeout 50 ms)
    printf("%.2f C, %.1f %%RH\n", t, rh);
    printf("temperature %.2f C\n", sensor.read_temperature());
    // sensor.read_temperature() → float °C (NAN on failure), single mode
    printf("humidity %.1f %%RH\n", sensor.read_humidity());
    // sensor.read_humidity() → float %RH (NAN on failure), single mode
    sensor.read_raw(raw_t, raw_rh);                                     // Read raw words, (temperature_raw, humidity_raw) → bool
                                                                        // 16-bit words, bits 1:0 zero
    printf("raw T %u raw RH %u\n", (unsigned)(raw_t), (unsigned)(raw_rh));

    sensor.set_resolution(11, 8);                                       // Set resolution, (temperature_bits, humidity_bits) → bool
                                                                        // T 14/11 bit, RH 14/11/8 bit; false for other values
    printf("conversion time %.2f ms\n", sensor.conversion_time() * 1000.0f);
    // sensor.conversion_time() → float s, typical T + RH time
    sensor.set_resolution(14, 14);                                      // Back to 14/14 bit

    sensor.set_heater(true);                                            // Switch the heater, (on) → void
                                                                        // runs only during measurements; readings drift while on
    printf("heater on %u\n", (unsigned)(sensor.is_heater_on()));
    // sensor.is_heater_on() → bool
    sensor.set_heater(false);                                           // Heater off
    printf("battery low %u\n", (unsigned)(sensor.battery_low()));
    // sensor.battery_low() → bool, true when VDD is below 2.8 V
    sensor.software_reset();                                            // Software reset, () → void
                                                                        // sets RST, waits 15 ms, rewrites the stored configuration

    printf("manufacturer 0x%04X\n", (unsigned)sensor.manufacturer_id());     // Manufacturer ID (0x5449), () → uint16_t
    printf("device 0x%04X\n", (unsigned)sensor.device_id());                 // Device ID (0x1050), () → uint16_t
    printf("done\n");
    while (true) vTaskDelay(pdMS_TO_TICKS(1000));
}
