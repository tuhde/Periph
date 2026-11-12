// SSD1305 demo example: SSD1305 128 x 64 OLED, Raspberry Pi (Linux GCC)
// Wiring: SDA=GPIO2, SCL=GPIO3 (/dev/i2c-1); VDD = 3V3
// Module: 1.3" 128 x 64 OLED (SSD1305, RAM columns 4..131), I2C address 0x3C (SA0 = low) or 0x3D (SA0 = high); pull-ups 4.7 k to 3.3 V.
// Demo: a bouncing ball inside a border, a contrast fade, inverse flashes, hardware scroll and sleep.
#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "SSD1305.h"

#define PRINT std::printf
#define DELAY_MS(ms) usleep((ms) * 1000u)

static void run(SSD1305Full& lcd) {
    // --- Draw the border once ---
    // A one-pixel border around the 128 x 64 panel; the ball bounces inside it.
    lcd.clear();                                    // Clear framebuffer, ()
    lcd.draw_rect(0, 0, 128, 64);                   // Draw rectangle, (x, y, w, h, on=true, fill=false)
    lcd.show();                                     // Send framebuffer to the display, ()

    // --- Bounce a 6 x 6 ball at about 20 frames per second ---
    // The ball is erased and redrawn in the framebuffer, then the frame is sent with show().
    int16_t bx = 20, by = 12, dx = 3, dy = 2;
    for (int frame = 0; frame < 200; frame++) {
        lcd.draw_rect(bx, by, 6, 6, false, true);   // Draw rectangle, (x, y, w, h, on=true, fill=false)
        bx += dx;
        by += dy;
        if (bx < 2 || bx > 120) { dx = -dx; bx += 2 * dx; }
        if (by < 2 || by > 56) { dy = -dy; by += 2 * dy; }
        lcd.draw_rect(bx, by, 6, 6, true, true);    // Draw rectangle, (x, y, w, h, on=true, fill=false)
        lcd.show();                                 // Send framebuffer to the display, ()
        DELAY_MS(50);
    }

    // --- Fade the contrast down and back up ---
    // 255 -> 0 -> 255 in steps of 5 shows the full range of the segment drive current.
    for (int v = 255; v >= 0; v -= 5) {
        lcd.set_contrast(static_cast<uint8_t>(v));  // Set contrast, (value 0..255)
        DELAY_MS(20);
    }
    for (int v = 0; v <= 255; v += 5) {
        lcd.set_contrast(static_cast<uint8_t>(v));  // Set contrast, (value 0..255)
        DELAY_MS(20);
    }

    // --- Flash the inverse display twice ---
    for (int i = 0; i < 2; i++) {
        lcd.set_invert(true);                       // Invert display, (on)
        DELAY_MS(400);
        lcd.set_invert(false);                      // Invert display, (on)
        DELAY_MS(400);
    }

    // --- Dim mode ---
    // Dim mode lowers contrast and colour-bank brightness to the levels stored with set_dim_mode.
    lcd.set_dim_mode(0x10, 0x10);                   // Configure dim levels, (contrast 0..255, brightness 0..255)
    lcd.set_dim(true);                              // Dim mode, (on)
    DELAY_MS(2000);
    lcd.set_dim(false);                             // Dim mode, (on)

    // --- Hardware scroll ---
    // The scroll engine moves all eight pages right in the controller without re-sending any data;
    // stopping rewrites the RAM from the framebuffer as the datasheet requires.
    lcd.set_scroll_horizontal(true, 0, 7, 0);       // Set horizontal scroll, (right, start_page, end_page, interval 0..6, columns=1)
    lcd.scroll_start();                             // Start scrolling, ()
    DELAY_MS(5000);
    lcd.scroll_stop();                              // Stop scrolling, ()

    // --- Power down ---
    // Sleep turns the display off (switch VCC off afterwards); RAM and registers are kept.
    lcd.sleep();                                    // Enter sleep, ()
}

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env) : 1;
    uint8_t addr = addr_env ? static_cast<uint8_t>(strtol(addr_env, nullptr, 0)) : 0x3C;
    I2CConnectionLinux conn(bus, addr);                          // Create I2C connection, (bus=1, addr=0x3C)
    static SSD1305Full lcd(conn);  // Create SSD1305 driver, (connection, width=128, height=64, column_offset=4)
    if (!lcd.valid()) {
        PRINT("SSD1305 init failed\n");
        return 1;
    }
    run(lcd);
    return 0;
}
