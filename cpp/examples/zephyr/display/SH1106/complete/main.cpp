// SH1106 complete example: SH1106 128 x 64 OLED, Zephyr (devicetree node label i2c0)
// Wiring: SDA/SCL = the i2c0 pins of the board; VDD = 3V3
// Module: 1.3" 128 x 64 OLED (SH1106, RAM columns 2..129), I2C address 0x3C (SA0 = VSS) or 0x3D (SA0 = VDD1); pull-ups 4.7 k to 3.3 V.
// Complete: every Full method - drawing, contrast, invert, flip, scroll, drive tuning, reads, sleep.
#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "SH1106.h"

#ifndef SH1106_I2C_NODE
#define SH1106_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef SH1106_ADDR
#define SH1106_ADDR 0x3C
#endif

#define PRINT printk
#define DELAY_MS(ms) k_msleep(ms)

static void run(SH1106Full& lcd) {
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
                                                    // segment drive current; reset value is 0x80
    DELAY_MS(800);
    lcd.set_contrast(0x80);                         // Set contrast, (value 0..255)
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
    lcd.set_multiplex(64);                          // Set multiplex ratio, (ratio 1..64)
                                                    // number of driven rows
    lcd.set_clock(1, 5);                            // Set clock, (divide 1..16, osc 0..15)
                                                    // divide ratio 1 at the nominal oscillator frequency
    lcd.set_precharge(2, 2);                        // Set pre-charge, (precharge 1..15, discharge 1..15)
                                                    // pre-charge and discharge periods in display clocks
    lcd.set_com_config(true);                       // Set COM pin configuration, (alternative)
                                                    // alternative (0x12) is the reset value and right for 128 x 64 modules
    lcd.set_vcom_deselect(0x35);                    // Set VCOM deselect level, (value 0..255)
    lcd.set_pump_voltage(0);                        // Set pump voltage, (level 0..3)
                                                    // 0 = 7.4 V, 1 = 8.0 V, 2 = 8.4 V, 3 = 9.0 V

    // Reads (I2C only) and raw commands.
    uint8_t status = 0;
    bool ok = lcd.read_status(status);              // Read status, (status&) → bool
                                                    // bit 7 BUSY, bit 6 = 1 when the display is off
    uint8_t ram[8];
    ok = lcd.read_page(0, 2, ram, 8);               // Read display RAM, (page 0..7, column, buf, n) → bool
                                                    // sets the address and discards the dummy byte
    (void)ok;
    static const uint8_t nop[1] = {0xE3};
    lcd.send_command(nop, 1);                       // Send raw command stream, (bytes, len)
                                                    // NOP

    // Power.
    lcd.set_display(false);                         // Display on/off, (on)
                                                    // RAM and settings are kept
    DELAY_MS(1000);
    lcd.set_display(true);                          // Display on/off, (on)
    lcd.sleep();                                    // Enter sleep, ()
                                                    // display off; the DC-DC stops
    DELAY_MS(1000);
    lcd.wake();                                     // Leave sleep, ()
                                                    // display on and a 100 ms wait for VPP
    lcd.set_dcdc(true);                             // Switch the internal DC-DC, (on)
                                                    // issue while the display is off; true = 0x8B, false = 0x8A for an external VPP
    PRINT("status 0x%02X\n", status);
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(SH1106_I2C_NODE);
    I2CConnectionZephyr conn(dev, SH1106_ADDR);                   // Create I2C connection, (dev, addr=0x3C)
    static SH1106Full lcd(conn);  // Create SH1106 driver, (connection, width=128, height=64, column_offset=2, internal_dcdc=true)
    if (!lcd.valid()) {
        PRINT("SH1106 init failed\n");
        return 1;
    }
    run(lcd);
    return 0;
}
