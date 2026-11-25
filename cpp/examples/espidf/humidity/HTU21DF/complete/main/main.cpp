#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "HTU21DF.h"

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
    HTU21DFFull sensor(connection);                                     // Create HTU21D-F driver, (connection) → void
    float t = 0, rh = 0;
    uint16_t raw_t = 0, raw_rh = 0;
    uint8_t reg = 0, res = 0;

    sensor.init();                                                      // Soft reset, () → void
                                                                        // 15 ms power-up wait, soft reset, 15 ms
    sensor.read(t, rh);                                                 // Read temperature and humidity, (t, rh) → bool °C, %RH
                                                                        // no hold master, CRC checked, one retry on a mismatch
    printf("%.2f C, %.1f %%RH\n", t, rh);
    printf("humidity %.1f %%RH\n", sensor.read_humidity());
    // sensor.read_humidity() → float %RH (NAN on failure), not clamped
    printf("temperature %.2f C\n", sensor.read_temperature());
    // sensor.read_temperature() → float °C (NAN on failure)
    printf("compensated humidity %.1f %%RH\n", sensor.read_humidity_compensated());
    // sensor.read_humidity_compensated() → float %RH, -0.15 %RH/°C relative to 25 °C (NAN on failure)
    sensor.read_raw(raw_t, raw_rh);                                     // Read raw codes, (temperature_raw, humidity_raw) → bool
                                                                        // 16-bit codes, status bits cleared
    printf("raw T %u raw RH %u\n", (unsigned)raw_t, (unsigned)raw_rh);

    sensor.set_resolution(3);                                           // Set resolution, (res) → bool
                                                                        // 0 = 12/14 bit RH/T, 1 = 8/12, 2 = 10/13, 3 = 11/11
    res = sensor.get_resolution();                                      // Get resolution, () → uint8_t
    printf("resolution setting %u\n", (unsigned)res);
    sensor.set_resolution(0);                                           // Back to 12/14 bit

    sensor.set_heater(true);                                            // Switch the heater, (on) → void
                                                                        // about 5.5 mW; readings drift while on
    printf("heater on %u\n", (unsigned)(sensor.heater()));
    // sensor.heater() → bool
    sensor.set_heater(false);                                           // Heater off
    printf("battery low %u\n", (unsigned)(sensor.battery_low()));
    // sensor.battery_low() → bool, true when VDD is below about 2.25 V
    reg = sensor.read_user_register();                          // Read the user register, () → uint8_t
                                                                        // 0x02 after reset
    printf("user register 0x%02X\n", (unsigned)reg);
    sensor.set_hold_master(false);                                      // Use hold-master commands, (on) → void
                                                                        // needs bus-layer clock stretching; off polls the NACKed read header
    printf("dew point %.2f C\n", sensor.dew_point());
    // sensor.dew_point() → float °C, one measurement (NAN on failure)
    sensor.soft_reset();                                                // Soft reset, () → void
                                                                        // 0xFE, waits 15 ms; the heater bit survives
    printf("done\n");
    while (true) vTaskDelay(pdMS_TO_TICKS(1000));
}
