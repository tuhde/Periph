// Auto-generated ESP-IDF example for APDS9960 (Demo).
// Mirrors the Arduino APDS9960_Demo example using the
// I2CConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "APDS9960.h"

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
        .device_address  = 0x39,
        .scl_speed_hz    = 400000,
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    APDS9960Full chip(connection);  // Create APDS9960 driver
    uint16_t c, r, g, b;
    uint8_t fifo_buf[128];
    uint8_t n;
    // --- Monitor ambient light with adaptive integration time ---
    // Start with the default 200 ms integration. When the clear channel approaches saturation, halve the integration time to prevent overflow.

    chip.configure_als(0xB6, 1);                      // Configure ALS, (atime 0-255, again 0-3) → void
    chip.is_als_valid();                              // Check ALS data valid, () → bool
    chip.color(c, r, g, b);                           // Read all RGBC channels, (clear, red, green, blue) → void
    chip.is_als_saturated();                          // Check ALS saturated, () → bool
    chip.configure_als(0xFE, 1);                      // Configure ALS, (atime 0-255, again 0-3) → void
    vTaskDelay(pdMS_TO_TICKS(1000));
}
