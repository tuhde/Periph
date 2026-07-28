// Auto-generated ESP-IDF example for BME680 (Demo).
// Mirrors the Arduino BME680_Demo example using the
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
    // --- Log T/P/H/gas with the heater on ---
    // BME680 needs the gas-sensor heater enabled; once a gas conversion completes, gas_resistance() returns the resistance reading.

    chip.configure(1, 1, 1, BME680Full::MODE_FORCED, BME680Full::FILTER_OFF);  // Configure, (osrs_t 0-5, osrs_p 0-5, osrs_h 0-5, mode, filter 0-7) → void
    chip.set_heater(320, 150);                        // Configure heater profile 0, (temp_c 200-400, duration_ms 1-4032) → void
    chip.read_all(t, p, h, g);                        // Read all values, (t, p, h, g) → void
    chip.temperature();                               // Read temperature, () → float °C
    chip.pressure();                                  // Read pressure, () → float hPa
    chip.humidity();                                  // Read humidity, () → float %RH
    chip.gas_resistance();                            // Read gas resistance, () → float Ω
    vTaskDelay(pdMS_TO_TICKS(1000));
}
