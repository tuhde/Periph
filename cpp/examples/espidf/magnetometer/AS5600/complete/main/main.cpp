// Auto-generated ESP-IDF example for AS5600 (Complete).
// Mirrors the Arduino AS5600_Complete example using the
// I2CConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "AS5600.h"

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
        .device_address  = 0x36,
        .scl_speed_hz    = 400000,
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    AS5600Full chip(connection);  // Create AS5600 driver
    float a;
    uint16_t r;
    uint8_t g;
    uint16_t zpos, mpos, mang;
    uint8_t burn;
    chip.is_magnet_detected();                        // Check magnet detected, () → bool
    chip.is_magnet_too_strong();                      // Check magnet too strong, () → bool
    chip.is_magnet_too_weak();                        // Check magnet too weak, () → bool
    chip.angle();                                     // Read absolute angle, () → float degrees
    chip.raw_angle();                                 // Read raw unscaled angle, () → int 0-4095
    chip.agc();                                       // Read AGC value, () → int
    chip.magnitude();                                 // Read CORDIC magnitude, () → uint16_t
    chip.raw_angle_degrees();                         // Read raw angle in degrees, () → float
    chip.status_byte();                               // Read raw STATUS register, () → uint8_t
    chip.configure(0, 0, 0, 0, 0, 0, false);          // Configure, (pm 0-3, hyst 0-3, outs 0-2, pwmf 0-3, sf 0-3, fth 0-7, wd) → void
    // writes CONF_H/CONF_L preserving reserved bits
    chip.zero_position();                             // Read zero position, () → uint16_t
    chip.max_position();                              // Read max position, () → uint16_t
    chip.max_angle();                                 // Read max angle, () → uint16_t
    chip.set_zero_position(0);                        // Set zero position, (pos 0-4095) → void
    chip.set_max_position(4095);                      // Set max position, (pos 0-4095) → void
    chip.set_max_angle(4095);                         // Set max angle, (span 0-4095) → void
    chip.burn_count();                                // Read burn count, () → uint8_t
    vTaskDelay(pdMS_TO_TICKS(1000));
}
