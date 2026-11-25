// SSD1306 minimal example: SSD1306 128 x 64 OLED, Zephyr (devicetree node label i2c0)
// Wiring: SDA/SCL = the i2c0 pins of the board; VDD = 3V3
// Module: 0.96" 128 x 64 OLED (SSD1306), I2C address 0x3C (SA0 = low) or 0x3D (SA0 = high); pull-ups 4.7 k to 3.3 V.
// Minimal use: a border and a diagonal on the 128 x 64 panel.
#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "SSD1306.h"

#ifndef SSD1306_I2C_NODE
#define SSD1306_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef SSD1306_ADDR
#define SSD1306_ADDR 0x3C
#endif

#define PRINT printk
#define DELAY_MS(ms) k_msleep(ms)

static void run(SSD1306Minimal& lcd) {
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
    const struct device *dev = DEVICE_DT_GET(SSD1306_I2C_NODE);
    I2CConnectionZephyr conn(dev, SSD1306_ADDR);                   // Create I2C connection, (dev, addr=0x3C)
    static SSD1306Minimal lcd(conn);  // Create SSD1306 driver, (connection, width=128, height=64, external_vcc=false)
    if (!lcd.valid()) {
        PRINT("SSD1306 init failed\n");
        return 1;
    }
    run(lcd);
    return 0;
}
