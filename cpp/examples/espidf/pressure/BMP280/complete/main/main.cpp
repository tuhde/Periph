// Auto-generated ESP-IDF example for BMP280 (Complete).
// Mirrors the Arduino BMP280_Complete example using the
// I2CTransportESPIDF transport.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CTransportESPIDF.h"
#include "BMP280.h"

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

    I2CTransportESPIDF transport(dev);
    BMP280Full chip(transport, false);  // Create BMP280 driver
    float t, p, alt, slp;
    uint8_t cid, st;
    chip.chip_id();                                   // Read chip ID, () → uint8_t
    // returns 0x58 for BMP280
    chip.configure(1, 1, 0, 0, 0);                    // Configure chip, (osrs_t 0-5, osrs_p 0-5, mode 0/1/3, filter 0-4, t_sb 0-7) → void
    // writes ctrl_meas and config
    chip.set_oversampling(BMP280Full::OSRS_X4, BMP280Full::OSRS_X2);  // Set oversampling, (osrs_t 0-5, osrs_p 0-5) → void
    chip.set_mode(BMP280Full::MODE_FORCED);           // Set power mode, (mode 0/1/3) → void
    chip.set_filter(BMP280Full::FILTER_4);            // Set IIR filter, (coeff 0-4) → void
    chip.set_standby(BMP280Full::T_SB_125_MS);        // Set standby time, (t_sb 0-7) → void
    chip.status();                                    // Read status register, () → uint8_t
    chip.temperature();                               // Read temperature, () → float °C
    chip.pressure();                                  // Read pressure, () → float hPa
    chip.altitude();                                  // Compute altitude, (sea_level_hpa=1013.25) → float m
    chip.sea_level_pressure(alt);                     // Compute sea-level pressure, (altitude_m) → float hPa
    chip.reset();                                     // Soft reset chip, () → void
    vTaskDelay(pdMS_TO_TICKS(1000));
}
