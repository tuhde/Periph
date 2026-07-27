// Auto-generated ESP-IDF example for PCF8591 (Complete).
// Mirrors the Arduino PCF8591_Complete example using the
// I2CConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "PCF8591.h"

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
        .device_address  = 0x48,
        .scl_speed_hz    = 400000,
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    PCF8591Full chip(connection);  // Create PCF8591 driver
    uint8_t adc[4];
    float vadc[4];
    chip.read_channel(0);                             // Read channel 0, (channel 0-3) → uint8_t
    chip.read_all(adc);                               // Read all 4 channels, (out[4]) → void
    chip.configure(PCF8591Full::MODE_4_SINGLE_ENDED, false, false);  // Configure, (input_mode 0-3, auto_increment, dac_enabled) → void
    chip.read_channel_voltage(0, 3.3f, 0.0f);         // Read channel voltage, (channel, vref, vagnd) → float V
    chip.read_all_voltage(vadc, 3.3f, 0.0f);          // Read all voltages, (out[4], vref, vagnd) → void
    chip.read_differential(0);                        // Read differential, (channel 0-2) → int8_t
    chip.set_dac(128);                                // Set DAC, (value 0-255) → void
    chip.set_dac_voltage(0.5f);                       // Set DAC voltage, (fraction 0.0-1.0) → void
    chip.disable_dac();                               // Disable DAC, () → void
    vTaskDelay(pdMS_TO_TICKS(1000));
}
