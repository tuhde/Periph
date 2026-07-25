// Auto-generated ESP-IDF example for MCP23017 (Complete).
// Mirrors the Arduino MCP23017_Complete example using the
// I2CTransportESPIDF transport.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CTransportESPIDF.h"
#include "MCP23017.h"

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
        .device_address  = 0x20,
        .scl_speed_hz    = 400000,
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CTransportESPIDF transport(dev);
    MCP23017Full chip(transport, 0x20);  // Create MCP23017 driver
    uint8_t val;
    chip.read_port(0);                                // Read all 8 pins of PORTA, (port 0/1) → uint8_t bitmask
    chip.write_port(0, 0x55);                         // Write all 8 pins of PORTA, (port, mask) → void
    chip.configure_pullup(0, 0xFF);                   // Enable pull-ups, (port, mask) → void
    // 100 kΩ internal pull-ups on PORTA
    chip.configure_polarity(0, 0x00);                 // Set input polarity, (port, mask) → void
    chip.set_default_value(0, 0xFF);                  // Set DEFVAL, (port, mask) → void
    // default-compare value for IOCON-DEFVAL interrupt mode
    chip.configure_interrupt(0, -1, nullptr, "change", false);  // Configure INT, (port, int_gpio_pin, callback, mode, mirror) → void
    // passing -1 disables hardware interrupt; falls back to polling on Linux
    chip.read_interrupt_flags(0);                     // Read INTFA, (port) → uint8_t
    chip.clear_interrupt(0);                          // Read INTCAPA, () → uint8_t; clears INT for the port
    chip.stop_interrupt(0);                           // Disable INT, (port) → void
    vTaskDelay(pdMS_TO_TICKS(1000));
}
