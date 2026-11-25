// SSD1305 minimal example: SSD1305 128 x 64 OLED, ESP-IDF (ESP32-S3 / ESP32, I2C_NUM_0)
// Wiring: SDA=GPIO21, SCL=GPIO22; VDD = 3V3
// Module: 1.3" 128 x 64 OLED (SSD1305, RAM columns 4..131), I2C address 0x3C (SA0 = low) or 0x3D (SA0 = high); pull-ups 4.7 k to 3.3 V.
// Minimal use: a border and a diagonal on the 128 x 64 panel.
#include <stdio.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <driver/i2c_master.h>
#include "I2CConnectionESPIDF.h"
#include "SSD1305.h"

#define DELAY_MS(ms) vTaskDelay(pdMS_TO_TICKS(ms))
#define PRINT printf

static void run(SSD1305Minimal& lcd) {
    lcd.clear();                                    // Clear framebuffer, ()
    for (int16_t x = 0; x < 128; x++) {
        lcd.set_pixel(x, 0);                        // Set pixel, (x 0..127, y 0..63, on=true)
        lcd.set_pixel(x, 63);                       // Set pixel, (x 0..127, y 0..63, on=true)
        lcd.set_pixel(x, static_cast<int16_t>(x / 2));  // Set pixel, (x 0..127, y 0..63, on=true)
    }
    for (int16_t y = 0; y < 64; y++) {
        lcd.set_pixel(0, y);                        // Set pixel, (x 0..127, y 0..63, on=true)
        lcd.set_pixel(127, y);                      // Set pixel, (x 0..127, y 0..63, on=true)
    }
    lcd.show();                                     // Send framebuffer to the display, ()
}

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

    // One device handle on the bus: address 0x3C (SA0 = low).
    i2c_device_config_t dev_cfg = {};
    dev_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev_cfg.device_address = 0x3C;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF conn(dev);                       // Create I2C connection, (dev)
    static SSD1305Minimal lcd(conn);  // Create SSD1305 driver, (connection, width=128, height=64, column_offset=4)
    if (!lcd.valid()) {
        PRINT("SSD1305 init failed\n");
        return;
    }
    run(lcd);
}
