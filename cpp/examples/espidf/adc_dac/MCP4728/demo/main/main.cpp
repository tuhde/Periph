// Auto-generated ESP-IDF example for MCP4728 (Demo).
// Mirrors the Arduino MCP4728_Demo example using the
// I2CConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
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

    I2CConnectionESPIDF connection(dev);
    MCP4728Full chip(connection);  // Create MCP4728 driver
    float fractions[4] = { 0.0f, 0.33f, 0.66f, 1.0f };
    uint8_t vrefs[4]   = { 0, 0, 0, 0 };
    uint8_t gains[4]   = { 0, 0, 0, 0 };
    // --- Step four channels in sequence ---
    // Each call updates one channel via Multi-Write, so V_OUT changes immediately.

    chip.set_voltage(0, 0.0f);                        // Set channel A output, (channel, fraction) → void
    chip.set_voltage(1, 0.33f);                       // Set channel B output, (channel, fraction) → void
    chip.set_voltage(2, 0.66f);                       // Set channel C output, (channel, fraction) → void
    chip.set_voltage(3, 1.0f);                        // Set channel D output, (channel, fraction) → void
    vTaskDelay(pdMS_TO_TICKS(1000));
}
