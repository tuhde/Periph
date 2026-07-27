// Auto-generated ESP-IDF example for MCP4725 (Complete).
// Mirrors the Arduino MCP4725_Complete example using the
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
    chip.set_voltage(0.25f);                          // Set DAC output, (fraction 0.0-1.0) → void
    chip.set_raw(2048);                               // Set raw 12-bit code, (code 0-4095) → void
    chip.set_voltage_eeprom(0.5f);                    // Set DAC and persist to EEPROM, (fraction) → void
    chip.set_raw_eeprom(2048);                        // Set raw code and persist to EEPROM, (code) → void
    chip.set_power_down(MCP4725Full::PD_1K_GND);      // Set power-down, (mode 0-3) → void
    chip.wake_up();                                   // Send General Call Wake-Up, () → void
    chip.reset();                                     // Send General Call Reset, () → void
    chip.is_eeprom_ready();                           // Check EEPROM write complete, () → bool
    chip.read();                                      // Read DAC + EEPROM, () → ReadResult
    vTaskDelay(pdMS_TO_TICKS(1000));
}
