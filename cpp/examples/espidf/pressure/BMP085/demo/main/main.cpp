// Auto-generated ESP-IDF example for BMP085 (Demo).
// Mirrors the Arduino BMP085_Demo example using the
// I2CConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "BMP085.h"

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {};
    bus_cfg.i2c_port = I2C_NUM_0;
    bus_cfg.sda_io_num = static_cast<gpio_num_t>(21);
    bus_cfg.scl_io_num = static_cast<gpio_num_t>(22);
    bus_cfg.clk_source = I2C_CLK_SRC_DEFAULT;
    bus_cfg.glitch_ignore_cnt = 7;
    bus_cfg.flags.enable_internal_pullup = true;
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {};
    dev_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev_cfg.device_address = 0x77;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    BMP085Full chip(connection);  // Create BMP085 driver
    float t, p, alt;
    uint8_t cid;
    int oss;
    // --- Pocket altimeter / weather logger ---
    // Reads temperature, pressure, and altitude at Ultra Low Power oversampling.
    // Demonstrates ~8 m altitude resolution per 1 hPa pressure change.

    chip.set_oversampling(BMP085Full::OSS_ULP);  // Set OSS, (oss 0-3) → void
    chip.temperature();                          // Read temperature, () → float °C
    chip.pressure();                             // Read pressure, () → float Pa
    chip.altitude();                             // Compute altitude, (sea_level_pa=101325.0) → float m
    vTaskDelay(pdMS_TO_TICKS(1000));
}