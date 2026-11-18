// Auto-generated ESP-IDF example for Is31fl3731 (Minimal).
// Mirrors the Arduino IS31FL3731_Minimal example using the
// I2CConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "Is31fl3731.h"

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {};
    bus_cfg.i2c_port = I2C_NUM_0;
    bus_cfg.sda_io_num = static_cast<gpio_num_t>(21);
    bus_cfg.scl_io_num = static_cast<gpio_num_t>(22);
    bus_cfg.clk_source = I2C_CLK_SRC_DEFAULT;
    bus_cfg.glitch_ignore_cnt = 7;
    bus_cfg.flags.enable_internal_pullup = true;
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {};
    dev_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev_cfg.device_address = 0x74;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    Is31fl3731Minimal matrix(connection);                 // Create IS31FL3731 driver, (connection)

    for (uint8_t col = 0; col < Is31fl3731Minimal::COLS; col++) {
        matrix.set_pixel(0, col, 128);                    // Set one LED's PWM brightness, (row 0–8, col 0–15, brightness 0–255) → bool
    }
    vTaskDelay(pdMS_TO_TICKS(1000));
    matrix.fill(32);                                      // Set every LED's PWM brightness, (brightness 0–255) → void
    vTaskDelay(pdMS_TO_TICKS(1000));
    matrix.clear();                                       // Switch all LEDs off, () → void

    while (1) {
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
