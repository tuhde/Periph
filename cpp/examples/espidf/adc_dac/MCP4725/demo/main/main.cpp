// Auto-generated ESP-IDF example for MCP4725 (Demo).
// Mirrors the Arduino MCP4725_Demo example using the
// I2CConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "MCP4725.h"

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
        .device_address  = 0x60,
        .scl_speed_hz    = 400000,
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    MCP4725Full chip(connection);  // Create MCP4725 driver
    uint16_t raw;
    auto rr = chip.read(); (void)rr;
    // --- Output a stepped waveform ---
    // Hold the DAC at 0%, 50%, 100%, and back. Useful as a sanity check on a new board - wire the output to an LED via resistor.

    chip.set_voltage(0.0f);                           // Set DAC output, (fraction 0.0-1.0) → void
    chip.set_voltage(0.5f);                           // Set DAC output, (fraction 0.0-1.0) → void
    chip.set_voltage(1.0f);                           // Set DAC output, (fraction 0.0-1.0) → void
    chip.set_voltage(0.25f);                          // Set DAC output, (fraction 0.0-1.0) → void
    vTaskDelay(pdMS_TO_TICKS(1000));
}
