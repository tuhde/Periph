// Auto-generated ESP-IDF example for BME680 (Complete).
// Mirrors the Arduino BME680_Complete example using the
// I2CConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "BME680.h"

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
    BME680Full chip(connection);  // Create BME680 driver
    float t, p, h, g;
    uint8_t cid;
    chip.chip_id();                                   // Read chip ID, () → uint8_t
    // returns 0x61 for BME680
    chip.configure(1, 1, 1, BME680Full::MODE_FORCED, BME680Full::FILTER_OFF);  // Configure, (osrs_t 0-5, osrs_p 0-5, osrs_h 0-5, mode, filter 0-7) → void
    // writes ctrl_hum, ctrl_meas, config in correct order
    chip.set_oversampling(BME680Full::OSRS_X2, BME680Full::OSRS_X4, BME680Full::OSRS_X1);  // Set oversampling, (osrs_t 0-5, osrs_p 0-5, osrs_h 0-5) → void
    chip.set_filter(BME680Full::FILTER_3);            // Set IIR filter, (coeff 0-7) → void
    chip.set_heater(320, 150);                        // Configure heater profile 0, (temp_c 200-400, duration_ms 1-4032) → void
    chip.set_gas_enabled(true);                       // Enable gas measurement, (enabled) → void
    chip.set_ambient_temperature(22.0f);              // Set ambient temperature, (temp_c) → void
    chip.read_all(t, p, h, g);                        // Read all values, (t, p, h, g) → void
    chip.gas_valid();                                 // Check gas data valid, () → bool
    chip.heater_stable();                             // Check heater stable, () → bool
    chip.status();                                    // Read status, () → uint8_t
    chip.temperature();                               // Read temperature, () → float °C
    chip.pressure();                                  // Read pressure, () → float hPa
    chip.humidity();                                  // Read humidity, () → float %RH
    chip.gas_resistance();                            // Read gas resistance, () → float Ω
    chip.reset();                                     // Soft reset chip, () → void
    vTaskDelay(pdMS_TO_TICKS(1000));
}
