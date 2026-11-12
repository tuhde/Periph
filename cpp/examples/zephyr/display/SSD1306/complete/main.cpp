// SSD1306 complete example: SSD1306 128 x 64 OLED, Zephyr (devicetree node label i2c0)
// Wiring: SDA/SCL = the i2c0 pins of the board; VDD = 3V3
// Module: 0.96" 128 x 64 OLED (SSD1306), I2C address 0x3C (SA0 = low) or 0x3D (SA0 = high); pull-ups 4.7 k to 3.3 V.
// Complete: every Full method - drawing, contrast, invert, flip, scroll, drive tuning, reads, sleep.
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

static void run(SSD1306Full& lcd) {
    // Framebuffer drawing: everything is composed in RAM and sent with show().
    lcd.clear();                                    // Clear framebuffer, ()
                                                    // zeroes every pixel of the framebuffer; the panel is unchanged until show()
    lcd.fill(true);                                 // Fill framebuffer, (on=true)
                                                    // sets (or clears) every pixel
    lcd.fill(false);                                // Fill framebuffer, (on=true)
    lcd.draw_rect(0, 0, 128, 64);                   // Draw rectangle, (x, y, w, h, on=true, fill=false)
                                                    // one-pixel outline around the whole panel
    lcd.draw_line(0, 0, 127, 63);                   // Draw line, (x0, y0, x1, y1, on=true)
                                                    // Bresenham line into the framebuffer
    lcd.draw_line(0, 63, 127, 0);                   // Draw line, (x0, y0, x1, y1, on=true)
    lcd.draw_rect(10, 10, 30, 20, true, true);      // Draw rectangle, (x, y, w, h, on=true, fill=false)
                                                    // filled rectangle
    lcd.draw_rect(18, 16, 14, 8, false, true);      // Draw rectangle, (x, y, w, h, on=true, fill=false)
                                                    // on=false clears pixels: a hole in the filled rectangle
    lcd.set_pixel(64, 32, true);                    // Set pixel, (x, y, on=true)
    bool lit = lcd.get_pixel(64, 32);               // Read framebuffer pixel, (x, y) → bool
                                                    // reads the framebuffer, not the panel
    (void)lit;
    static const uint8_t icon[16] = {0x3C, 0x42, 0x81, 0xA5, 0x81, 0xA5, 0x99, 0x42, 0x3C, 0x00, 0x7E, 0x81, 0x81, 0x81, 0x7E, 0x00};
    lcd.blit(100, 16, icon, sizeof(icon), 2);       // Copy bitmap, (x, y multiple of 8, columns, len, height_pages=1)
                                                    // two page rows of 8 columns, bit 0 = top pixel
    lcd.show();                                     // Send framebuffer to the display, ()
                                                    // per page: page + column command, then 128 data bytes in 31-byte chunks
    DELAY_MS(2000);
    lcd.show_page(2);                               // Send one framebuffer page, (page 0..7)
                                                    // cheaper partial update of 8 rows

    // Contrast and display modes.
    lcd.set_contrast(0x20);                         // Set contrast, (value 0..255)
                                                    // segment drive current; reset value is 0x7F
    DELAY_MS(800);
    lcd.set_contrast(0x7F);                         // Set contrast, (value 0..255)
    lcd.set_invert(true);                           // Invert display, (on)
                                                    // RAM 0 = pixel on; RAM is unchanged
    DELAY_MS(800);
    lcd.set_invert(false);                          // Invert display, (on)
    lcd.set_all_on(true);                           // Force all pixels on, (on)
                                                    // test mode, RAM is kept
    DELAY_MS(800);
    lcd.set_all_on(false);                          // Force all pixels on, (on)
    lcd.set_flip(true, true);                       // Mirror display, (horizontal, vertical)
                                                    // segment re-map and COM scan direction; framebuffer unchanged
    DELAY_MS(1500);
    lcd.set_flip(false, false);                     // Mirror display, (horizontal, vertical)

    // Hardware vertical scroll and drive tuning.
    for (uint8_t line = 0; line < 64; line += 2) {
        lcd.set_start_line(line);                   // Scroll the image up, (line 0..63)
                                                    // RAM line shown at COM0; the image wraps around
        DELAY_MS(30);
    }
    lcd.set_start_line(0);                          // Scroll the image up, (line 0..63)
    lcd.set_display_offset(0);                      // Set COM offset, (offset 0..63)
                                                    // shifts the COM mapping
    lcd.set_multiplex(64);                          // Set multiplex ratio, (ratio 16..64)
                                                    // number of driven rows
    lcd.set_clock(1, 8);                            // Set clock, (divide 1..16, osc 0..15)
                                                    // divide ratio 1 at the reset oscillator setting
    lcd.set_precharge(2, 2);                        // Set pre-charge, (phase1 1..15, phase2 1..15)
                                                    // pre-charge and discharge periods in display clocks
    lcd.set_com_config(true);                       // Set COM pin configuration, (alternative, remap=false)
                                                    // alternative is the reset value and right for 128 x 64 modules
    lcd.set_vcomh(0x20);                            // Set VCOMH deselect level, (value 0x00..0x70)
                                                    // 0x20 (about 0.77 x VCC) is the reset value
    lcd.set_charge_pump(true);                      // Enable the charge pump, (enabled)
                                                    // 0x8D 0x14; takes effect with the display on

    // Addressing modes.
    lcd.set_addressing_mode(SSD1306Minimal::ADDR_HORIZONTAL);// Select addressing mode, (mode ADDR_PAGE|ADDR_HORIZONTAL|ADDR_VERTICAL)
                                                    // show() then streams the frame in one transfer
    lcd.show();                                     // Update display, () → bool
    lcd.set_addressing_mode(SSD1306Minimal::ADDR_PAGE);// Select addressing mode, (mode ADDR_PAGE|ADDR_HORIZONTAL|ADDR_VERTICAL)

    // Hardware scrolling (RAM access is refused while a scroll is active).
    lcd.set_scroll_horizontal(true, 0, 7, 7);       // Set horizontal scroll, (right, start_page, end_page, interval 0..7)
                                                    // interval index 7 = 2 frames per step
    lcd.scroll_start();                             // Start scrolling, ()
    DELAY_MS(1000);
    lcd.scroll_stop();                              // Stop scrolling, ()
                                                    // deactivates and rewrites the RAM from the framebuffer
    lcd.set_scroll_area(0, 64);                     // Set vertical scroll area, (top_fixed 0..63, scroll_rows 1..64)
    lcd.set_scroll_vertical(true, 0, 7, 7, 1);      // Set diagonal scroll, (right, start_page, end_page, interval 0..7, rows 1..63)
    lcd.scroll_start();                             // Start scrolling, ()
    DELAY_MS(1000);
    lcd.scroll_stop();                              // Stop scrolling, ()

    // Raw commands.
    static const uint8_t nop[1] = {0xE3};
    lcd.send_command(nop, 1);                       // Send raw command stream, (bytes, len)
                                                    // NOP

    // Power.
    lcd.set_display(false);                         // Display on/off, (on)
                                                    // RAM and settings are kept
    DELAY_MS(1000);
    lcd.set_display(true);                          // Display on/off, (on)
    lcd.sleep();                                    // Enter sleep, ()
                                                    // display off; switch VCC off afterwards
    DELAY_MS(1000);
    lcd.wake();                                     // Leave sleep, ()
                                                    // display on and a 100 ms wait (tAF)
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(SSD1306_I2C_NODE);
    I2CConnectionZephyr conn(dev, SSD1306_ADDR);                   // Create I2C connection, (dev, addr=0x3C)
    static SSD1306Full lcd(conn);  // Create SSD1306 driver, (connection, width=128, height=64, external_vcc=false)
    if (!lcd.valid()) {
        PRINT("SSD1306 init failed\n");
        return 1;
    }
    run(lcd);
    return 0;
}
