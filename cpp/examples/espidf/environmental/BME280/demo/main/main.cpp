// Auto-generated ESP-IDF example for BME280 (Demo).
// Mirrors the Arduino BME280_Demo example using the
// I2CConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "BME280.h"

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {
        .i2c_port = I2C_NUM_0,
        .sda_io_num = static_cast<gpio_num_t>(21),
        .scl_io_num = static_cast<gpio_num_t>(22),
        .clk_source = I2C_CLK_SRC_DEFAULT,
        .glitch_ignore_cnt = 7,
        .flags = { .enable_internal_pullup = true },
    };
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address  = 0x76,
        .scl_speed_hz    = 400000,
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    BME280Full chip(connection, false);  // Create BME280 driver
    float t, p, h, alt, slp, dp;
    uint8_t cid, st;
    // --- Weather monitoring preset: forced mode, x1/x1/x1, filter off ---
    // BME280 datasheet 'weather monitoring' preset: minimum power, single-shot, ~9 ms per cycle.

    chip.configure(BME280Full::OSRS_X1, BME280Full::OSRS_X1, BME280Full::OSRS_X1, BME280Full::MODE_FORCED, BME280Full::FILTER_OFF, BME280Full::T_SB_0_5_MS);  // Configure chip, (osrs_t=×1, osrs_p=×1, osrs_h=×1, mode=forced, filter=off, t_sb=0) → void
    chip.temperature();                               // Read temperature, () → float °C
    chip.pressure();                                  // Read pressure, () → float hPa
    chip.humidity();                                  // Read humidity, () → float %RH
    chip.altitude();                                  // Compute altitude, (sea_level_hpa=1013.25) → float m
    chip.dew_point();                                 // Compute dew point, () → float °C
    chip.temperature();                               // Read temperature, () → float °C
    chip.pressure();                                  // Read pressure, () → float hPa
    chip.humidity();                                  // Read humidity, () → float %RH
    chip.dew_point();                                 // Compute dew point, () → float °C
    vTaskDelay(pdMS_TO_TICKS(1000));
}
