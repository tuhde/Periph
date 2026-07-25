// Auto-generated ESP-IDF example for AS5600 (Demo).
// Mirrors the Arduino AS5600_Demo example using the
// I2CTransportESPIDF transport.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CTransportESPIDF.h"
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

    I2CTransportESPIDF transport(dev);
    AS5600Full chip(transport);  // Create AS5600 driver
    float a;
    uint16_t r;
    uint8_t g, prev_status, status;
    // --- Sample angle / raw / AGC 10 times, alerting on status changes ---
    // Magnet detection is a sticky bit; watching it tells you when a magnet is inserted or removed. AGC tells you magnet distance: 5 V mode target ~ 128, 3.3 V mode target ~ 64.

    chip.status_byte();                               // Read raw STATUS register, () → uint8_t
    chip.angle();                                     // Read absolute angle, () → float degrees
    chip.raw_angle();                                 // Read raw unscaled angle, () → int 0-4095
    chip.agc();                                       // Read AGC value, () → int
    chip.status_byte();                               // Read raw STATUS register, () → uint8_t
    chip.is_magnet_detected();                        // Check magnet detected, () → bool
    chip.is_magnet_too_strong();                      // Check magnet too strong, () → bool
    chip.is_magnet_too_weak();                        // Check magnet too weak, () → bool
    vTaskDelay(pdMS_TO_TICKS(1000));
}
