// Auto-generated ESP-IDF example for BME280 (Complete).
// Mirrors the Arduino BME280_Complete example using the
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
    chip.chip_id();                                   // Read chip ID, () → uint8_t
    // returns 0x60 for BME280
    chip.configure(1, 1, 1, 0, 0, 0);                 // Configure chip, (osrs_t 0–5, osrs_p 0–5, osrs_h 0–5, mode 0/1/3, filter 0–4, t_sb 0–7) → void
    // writes ctrl_hum, config, ctrl_meas in correct order
    chip.set_oversampling(BME280Full::OSRS_X4, BME280Full::OSRS_X2, BME280Full::OSRS_X1);  // Set oversampling, (osrs_t 0–5, osrs_p 0–5, osrs_h 0–5) → void
    // humidity update requires ctrl_meas write to latch
    chip.set_mode(BME280Full::MODE_FORCED);           // Set power mode, (mode 0/1/3) → void
    chip.set_filter(BME280Full::FILTER_4);            // Set IIR filter, (coeff 0–4) → void
    // suppresses short-term pressure disturbances
    chip.set_standby(BME280Full::T_SB_125_MS);        // Set standby time, (t_sb 0–7) → void
    // only relevant in normal mode; codes 6/7 mean 10/20 ms on BME280
    chip.status();                                    // Read status register, () → uint8_t
    chip.temperature();                               // Read temperature, () → float °C
    chip.pressure();                                  // Read pressure, () → float hPa
    chip.humidity();                                  // Read humidity, () → float %RH
    chip.altitude();                                  // Compute altitude, (sea_level_hpa=1013.25) → float m
    // uses barometric formula to convert pressure to metres
    chip.sea_level_pressure(alt);                     // Compute sea-level pressure, (altitude_m) → float hPa
    chip.dew_point();                                 // Compute dew point, () → float °C
    // Magnus-Tetens approximation from current T and RH
    chip.reset();                                     // Soft reset chip, () → void
    // re-reads calibration and re-applies configuration
    vTaskDelay(pdMS_TO_TICKS(1000));
}
