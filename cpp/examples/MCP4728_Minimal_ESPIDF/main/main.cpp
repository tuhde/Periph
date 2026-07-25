// Auto-generated ESP-IDF example for MCP4728 (Minimal).
// Mirrors the Arduino MCP4728_Minimal example using the
// I2CTransportESPIDF transport.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CTransportESPIDF.h"
#include "MCP4728.h"

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

    I2CTransportESPIDF transport(dev);
    MCP4728Minimal chip(transport);  // Create MCP4728 driver
    float fractions[4] = { 0.0f, 0.33f, 0.66f, 1.0f };
    uint8_t vrefs[4]   = { 0, 0, 0, 0 };
    uint8_t gains[4]   = { 0, 0, 0, 0 };
    while (1) {
    chip.set_voltage(0, 0.5f);                        // Set channel A output, (channel 0-3, fraction 0.0-1.0) → void
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
