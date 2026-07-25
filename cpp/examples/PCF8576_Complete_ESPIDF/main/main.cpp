// Auto-generated ESP-IDF example for PCF8576 (Complete).
// Mirrors the Arduino PCF8576_Complete example using the
// I2CTransportESPIDF transport.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CTransportESPIDF.h"
#include "PCF8576.h"

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
        .device_address  = 0x38,
        .scl_speed_hz    = 400000,
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CTransportESPIDF transport(dev);
    PCF8576Full chip(transport);  // Create PCF8576 driver
    uint8_t raw[40];
    chip.clear();                                     // Clear display RAM, () → void
    chip.write_raw(0, raw, 4);                        // Write raw bytes, (address 0-39, data, len) → void
    chip.set_digit_7seg(0, PCF8576Minimal::SEVEN_SEG[1]);  // Write one 7-segment byte, (position, segments) → void
    chip.set_digit_7seg(1, PCF8576Minimal::SEVEN_SEG[2]);  // Write one 7-segment byte, (position, segments) → void
    chip.set_digit_7seg(2, PCF8576Minimal::SEVEN_SEG[3]);  // Write one 7-segment byte, (position, segments) → void
    chip.set_digit_7seg(3, PCF8576Minimal::SEVEN_SEG[4]);  // Write one 7-segment byte, (position, segments) → void
    chip.enable();                                    // Turn display on, () → void
    chip.disable();                                   // Blank the display, () → void
    chip.set_mode(PCF8576Full::BACKPLANES_4, PCF8576Full::BIAS_1_3);  // Set mode, (backplanes 1-4, bias 0/1) → void
    chip.set_blink(PCF8576Full::BLINK_1_HZ);          // Set blink, (frequency 0-3, alternate_bank=false) → void
    chip.set_bank(0, 0);                              // Select RAM bank, (input_bank 0/1, output_bank 0/1) → void
    chip.device_select(0);                            // Change subaddress counter, (subaddress 0-7) → void
    vTaskDelay(pdMS_TO_TICKS(1000));
}
