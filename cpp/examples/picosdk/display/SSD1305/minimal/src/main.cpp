// SSD1305 minimal example: SSD1305 128 x 64 OLED, Raspberry Pi Pico SDK (Pico / Pico W, i2c0)
// Wiring: SDA=GP4, SCL=GP5; VDD = 3V3
// Module: 1.3" 128 x 64 OLED (SSD1305, RAM columns 4..131), I2C address 0x3C (SA0 = low) or 0x3D (SA0 = high); pull-ups 4.7 k to 3.3 V.
// Minimal use: a border and a diagonal on the 128 x 64 panel.
#include <stdio.h>
#include <hardware/i2c.h>
#include <hardware/gpio.h>
#include <pico/stdlib.h>
#include "I2CConnectionPicoSDK.h"
#include "SSD1305.h"

#define DELAY_MS(ms) sleep_ms(ms)
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

int main(void) {
    stdio_init_all();
    sleep_ms(2000);

    i2c_init(i2c0, 400 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);

    I2CConnectionPicoSDK conn(i2c0, 0x3C);                // Create I2C connection, (i2c, addr=0x3C)
    static SSD1305Minimal lcd(conn);  // Create SSD1305 driver, (connection, width=128, height=64, column_offset=4)
    if (!lcd.valid()) {
        PRINT("SSD1305 init failed\n");
        return 1;
    }
    run(lcd);
    return 0;
}
