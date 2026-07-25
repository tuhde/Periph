// Auto-generated ESP-IDF example for MCP4728 (Complete).
// Mirrors the Arduino MCP4728_Complete example using the
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
    MCP4728Full chip(transport);  // Create MCP4728 driver
    float fractions[4] = { 0.0f, 0.33f, 0.66f, 1.0f };
    uint8_t vrefs[4]   = { 0, 0, 0, 0 };
    uint8_t gains[4]   = { 0, 0, 0, 0 };
    chip.set_voltage(0, 0.25f);                       // Set channel A output, (channel, fraction) → void
    chip.set_voltage(1, 0.5f);                        // Set channel B output, (channel, fraction) → void
    chip.set_voltage(2, 0.75f);                       // Set channel C output, (channel, fraction) → void
    chip.set_voltage(3, 1.0f);                        // Set channel D output, (channel, fraction) → void
    chip.set_all(fractions);                          // Update all four channels, (fractions[4]) → void
    chip.set_vref(MCP4728Full::VREF_EXTERNAL, MCP4728Full::VREF_EXTERNAL, MCP4728Full::VREF_EXTERNAL, MCP4728Full::VREF_EXTERNAL);  // Set V_REF, (vref_a-d) → void
    chip.set_gain(MCP4728Full::GAIN_X1, MCP4728Full::GAIN_X1, MCP4728Full::GAIN_X1, MCP4728Full::GAIN_X1);  // Set gain, (gain_a-d) → void
    chip.set_power_down(MCP4728Full::PD_NORMAL, MCP4728Full::PD_NORMAL, MCP4728Full::PD_NORMAL, MCP4728Full::PD_NORMAL);  // Set power-down, (pd_a-d) → void
    chip.set_voltage_eeprom(0, 0.5f, MCP4728Full::VREF_EXTERNAL, MCP4728Full::GAIN_X1);  // Set + persist to EEPROM, (channel, fraction, vref, gain) → void
    chip.set_all_eeprom(fractions, vrefs, gains);     // Update all + persist, (fractions[4], vrefs[4], gains[4]) → void
    chip.is_eeprom_ready();                           // Check EEPROM ready, () → bool
    chip.software_update();                           // Send General Call software update, () → void
    chip.wake_up();                                   // Send General Call wake-up, () → void
    chip.reset();                                     // Send General Call reset, () → void
    chip.read();                                      // Read all channels, () → ReadResult
    vTaskDelay(pdMS_TO_TICKS(1000));
}
