// UC1611s demo example: EA DOGXL240-7, Raspberry Pi Pico SDK (Pico / Pico W, i2c0)
// Wiring: SDA=GP4, SCL=GP5; VDD = 3V3
// Module: EA DOGXL240-7 (240 x 128, 3.3 V only), I2C: BM0 = D13 = VDD, CD = A2 = A3 = VSS -> command 0x38 / data 0x39.
// Capacitors: 4 x 4.7 uF/5 V (VA0, VA1, VB0, VB1 pairs), 470 nF/25 V on VLCD; pull-ups 4.7 k to 3.3 V.
// Demo: a bouncing ball with partial page updates, a contrast sweep, inverse flashes, hardware scroll, a second RAM screen and sleep.
#include <stdio.h>
#include <hardware/i2c.h>
#include <hardware/gpio.h>
#include <pico/stdlib.h>
#include "I2CConnectionPicoSDK.h"
#include "UC1611s.h"

#define DELAY_MS(ms) sleep_ms(ms)
#define PRINT printf

static void run(UC1611sFull& lcd) {
    // --- Draw the frame once ---
    // A one-pixel border around the whole 240 x 128 panel; the ball bounces inside it.
    lcd.clear();                                    // Clear framebuffer, ()
    lcd.draw_rect(0, 0, 240, 128);                  // Draw rectangle, (x, y, w, h, on=true, fill=false)
    lcd.show();                                     // Send framebuffer to the display, ()
    
    // --- Bounce an 8 x 8 ball with partial page updates ---
    // Only the page rows the ball touches are re-sent, so the animation runs at about
    // 20 frames per second even at 400 kHz, instead of the 8 of a full-frame show().
    int16_t bx = 20, by = 12, dx = 3, dy = 2;
    for (int frame = 0; frame < 200; frame++) {
        int16_t oldy = by;
        lcd.draw_rect(bx, by, 8, 8, false, true);   // Draw rectangle, (x, y, w, h, on=true, fill=false)
        bx += dx;
        by += dy;
        if (bx < 2 || bx > 229) { dx = -dx; bx += 2 * dx; }
        if (by < 2 || by > 118) { dy = -dy; by += 2 * dy; }
        lcd.draw_rect(bx, by, 8, 8, true, true);    // Draw rectangle, (x, y, w, h, on=true, fill=false)
        int16_t first = (oldy < by ? oldy : by) / 8;
        int16_t last = ((oldy > by ? oldy : by) + 7) / 8;
        for (int16_t page = first; page <= last; page++) {
            lcd.show_page(static_cast<uint8_t>(page));  // Send one framebuffer page, (page 0..15)
        }
        DELAY_MS(20);
    }
    
    // --- Contrast sweep around the working point ---
    // The contrast is set once in normal use; sweeping PM shows the usable range of the panel.
    for (int pm = 0x8F; pm >= 0x70; pm -= 2) {
        lcd.set_contrast(static_cast<uint8_t>(pm));  // Set contrast, (value 0..255)
        DELAY_MS(40);
    }
    for (int pm = 0x70; pm <= 0x8F; pm += 2) {
        lcd.set_contrast(static_cast<uint8_t>(pm));  // Set contrast, (value 0..255)
        DELAY_MS(40);
    }
    
    // --- Flash the inverse display twice ---
    for (int i = 0; i < 2; i++) {
        lcd.set_invert(true);                       // Invert display, (on)
        DELAY_MS(400);
        lcd.set_invert(false);                      // Invert display, (on)
        DELAY_MS(400);
    }
    
    // --- Hardware scroll ---
    // The image moves up in the controller, no data is re-sent; a blank band enters from the bottom
    // because RAM rows 128..159 are empty.
    for (uint8_t line = 0; line <= 32; line++) {
        lcd.set_scroll_line(line);                  // Scroll the image up, (line 0..159 - 2 * fixed)
        DELAY_MS(40);
    }
    for (int line = 32; line >= 0; line--) {
        lcd.set_scroll_line(static_cast<uint8_t>(line));  // Scroll the image up, (line 0..159 - 2 * fixed)
        DELAY_MS(40);
    }
    
    // --- Second screen in RAM ---
    // The chip holds four black/white screens; draw a cross into screen 1 and switch to it.
    lcd.set_write_pattern(1);                       // Select RAM screen to write, (pattern 0..3)
    lcd.fill(false);                                // Fill framebuffer, (on=true)
    lcd.draw_line(0, 0, 239, 127);                  // Draw line, (x0, y0, x1, y1, on=true)
    lcd.draw_line(0, 127, 239, 0);                  // Draw line, (x0, y0, x1, y1, on=true)
    lcd.show();                                     // Send framebuffer to the display, ()
    lcd.set_display_pattern(1);                     // Select displayed RAM screen, (pattern 0..3)
    DELAY_MS(2000);
    lcd.set_display_pattern(0);                     // Select displayed RAM screen, (pattern 0..3)
    lcd.set_write_pattern(0);                       // Select RAM screen to write, (pattern 0..3)
    DELAY_MS(1500);
    
    // --- Power down ---
    // Sleep stops the charge pump and the drivers (below 1 uA); RAM and registers are kept.
    lcd.sleep();                                    // Enter sleep, ()
    PRINT("done\n");
}

int main(void) {
    stdio_init_all();
    sleep_ms(2000);

    i2c_init(i2c0, 400 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);

    I2CConnectionPicoSDK cmdConn(i2c0, 0x38);             // Create command connection, (i2c, addr=0x38)
    I2CConnectionPicoSDK dataConn(i2c0, 0x39);            // Create data connection, (i2c, addr=0x39)
    static UC1611sFull lcd(cmdConn, dataConn);  // Create UC1611s driver, (command, data, width=240, height=128)
    if (!lcd.valid()) {
        PRINT("UC1611s init failed\n");
        return 1;
    }
    run(lcd);
    return 0;
}
