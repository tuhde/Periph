// Auto-generated ESP-IDF example for BME688 (Complete).
// Mirrors the Arduino BME688_Complete example using the
// I2CConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "BME688.h"

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
    dev_cfg.device_address = 0x76;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    BME688Full chip(connection);  // Create BME688 driver
    float t, p, h, g;
    uint8_t cid;
    chip.chip_id();                                   // Read chip ID, () → uint8_t
    // returns 0x61 for BME688
    chip.configure(1, 1, 1, BME688Full::MODE_FORCED, BME688Full::FILTER_0);    // Configure, (osrs_t 0-5, osrs_p 0-5, osrs_h 0-5, mode, filter 0-7) → void
    // writes ctrl_hum, ctrl_meas, config in correct order
    chip.set_oversampling(BME688Full::OSRS_X2, BME688Full::OSRS_X4, BME688Full::OSRS_X1);  // Set oversampling, (osrs_t 0-5, osrs_p 0-5, osrs_h 0-5) → void
    chip.set_filter(BME688Full::FILTER_3);            // Set IIR filter, (coeff 0-7) → void
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
    uint8_t vid = chip.variant_id();                        // Read variant ID, () → uint8_t
                                                        // 0x01 for the BME688 (tells it apart from the BME680)
    (void)vid;
    const int16_t ptemps[2] = {320, 250};
    const uint8_t prepeats[2] = {1, 2};
    chip.start_parallel(ptemps, prepeats, 2, 100.0f);    // Start parallel mode, (temps, repeats, steps 1–10, shared_wait_ms) → void
                                                        // programs res_heat_x, gas_wait_x, gas_wait_shared, nb_conv, then mode=parallel
    BME688Field fields[3];
    uint8_t nfields = chip.read_parallel(fields, 3);     // Read new data fields, (out, max) → uint8_t count
    (void)nfields;
    chip.stop_parallel();                                // Leave parallel mode, () → void
    chip.reset();                                     // Soft reset chip, () → void
    vTaskDelay(pdMS_TO_TICKS(1000));
}
