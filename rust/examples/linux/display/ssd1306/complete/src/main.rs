//! SSD1306 complete example: 0.96" 128 x 64 OLED module (SSD1306) over I2C on a Raspberry Pi (Linux host).
//!
//! Wiring: SDA=GPIO2, SCL=GPIO3 (/dev/i2c-1), VCC = 3V3 (the module generates the OLED supply with the SSD1306 charge pump); address 0x3C (SA0 = low) or 0x3D (SA0 = high). Complete: every Full method - drawing, contrast, invert, flip, scroll, drive tuning, reads, sleep.
use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::display::ssd1306::{AddressingMode, Ssd1306Full};

fn sleep(ms: u64) {
    std::thread::sleep(std::time::Duration::from_millis(ms));
}

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x3C);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut lcd = Ssd1306Full::new(dev, addr, Delay, 128, 64, false).expect("init SSD1306"); // Create SSD1306 driver, (i2c, addr=0x3C, delay, width=128, height=64, external_vcc=false)

    // Framebuffer drawing: everything is composed in RAM and sent with show().
    lcd.clear();                                                                // Clear framebuffer, ()
                                                                                // zeroes every pixel of the framebuffer; the panel is unchanged until show()
    lcd.fill(true);                                                             // Fill framebuffer, (on)
                                                                                // sets (or clears) every pixel
    lcd.fill(false);                                                            // Fill framebuffer, (on)
    lcd.draw_rect(0, 0, 128, 64, true, false);                                  // Draw rectangle, (x, y, w, h, on, fill)
                                                                                // one-pixel outline around the whole panel
    lcd.draw_line(0, 0, 127, 63, true);                                         // Draw line, (x0, y0, x1, y1, on)
                                                                                // Bresenham line into the framebuffer
    lcd.draw_line(0, 63, 127, 0, true);                                         // Draw line, (x0, y0, x1, y1, on)
    lcd.draw_rect(10, 10, 30, 20, true, true);                                  // Draw rectangle, (x, y, w, h, on, fill)
                                                                                // filled rectangle
    lcd.draw_rect(18, 16, 14, 8, false, true);                                  // Draw rectangle, (x, y, w, h, on, fill)
                                                                                // on=false clears pixels: a hole in the filled rectangle
    lcd.set_pixel(64, 32, true);                                                // Set pixel, (x, y, on)
    let lit = lcd.get_pixel(64, 32);                                            // Read framebuffer pixel, (x, y) → bool
                                                                                // reads the framebuffer, not the panel
    let icon = [0x3C, 0x42, 0x81, 0xA5, 0x81, 0xA5, 0x99, 0x42, 0x3C, 0x00, 0x7E, 0x81, 0x81, 0x81, 0x7E, 0x00];
    lcd.blit(100, 16, &icon, 2).expect("blit");                                 // Copy bitmap, (x, y multiple of 8, columns, height_pages) → ()
                                                                                // two page rows of 8 columns, bit 0 = top pixel
    lcd.show().expect("show");                                                  // Send framebuffer to the display, () → ()
                                                                                // per page: page + column command, then 128 data bytes in 31-byte chunks
    sleep(2000);
    lcd.show_page(2).expect("show_page");                                       // Send one framebuffer page, (page 0..7) → ()
                                                                                // cheaper partial update of 8 rows

    // Contrast and display modes.
    lcd.set_contrast(0x20).expect("set_contrast");                              // Set contrast, (value 0..255) → ()
                                                                                // segment drive current; reset value is 0x7F
    sleep(800);
    lcd.set_contrast(0x7F).expect("set_contrast");                              // Set contrast, (value 0..255) → ()
    lcd.set_invert(true).expect("set_invert");                                  // Invert display, (on) → ()
                                                                                // RAM 0 = pixel on; RAM is unchanged
    sleep(800);
    lcd.set_invert(false).expect("set_invert");                                 // Invert display, (on) → ()
    lcd.set_all_on(true).expect("set_all_on");                                  // Force all pixels on, (on) → ()
                                                                                // test mode, RAM is kept
    sleep(800);
    lcd.set_all_on(false).expect("set_all_on");                                 // Force all pixels on, (on) → ()
    lcd.set_flip(true, true).expect("set_flip");                                // Mirror display, (horizontal, vertical) → ()
                                                                                // segment re-map and COM scan direction; framebuffer unchanged
    sleep(1500);
    lcd.set_flip(false, false).expect("set_flip");                              // Mirror display, (horizontal, vertical) → ()

    // Hardware vertical scroll and drive tuning.
    for line in (0..64).step_by(2) {
        lcd.set_start_line(line).expect("set_start_line");                      // Scroll the image up, (line 0..63) → ()
                                                                                // RAM line shown at COM0; the image wraps around
        sleep(30);
    }
    lcd.set_start_line(0).expect("set_start_line");                             // Scroll the image up, (line 0..63) → ()
    lcd.set_display_offset(0).expect("set_display_offset");                     // Set COM offset, (offset 0..63) → ()
                                                                                // shifts the COM mapping
    lcd.set_multiplex(64).expect("set_multiplex");                              // Set multiplex ratio, (ratio 16..64) → ()
                                                                                // number of driven rows
    lcd.set_clock(1, 8).expect("set_clock");                                    // Set clock, (divide 1..16, osc 0..15) → ()
                                                                                // divide ratio 1 at the reset oscillator setting
    lcd.set_precharge(2, 2).expect("set_precharge");                            // Set pre-charge, (phase1 1..15, phase2 1..15) → ()
                                                                                // pre-charge and discharge periods in display clocks
    lcd.set_com_config(true, false).expect("set_com_config");                          // Set COM pin configuration, (alternative, remap) → ()
                                                                                // alternative is the reset value and right for 128 x 64 modules
    lcd.set_vcomh(0x20).expect("set_vcomh");                                    // Set VCOMH deselect level, (value 0x00..0x70) → ()
                                                                                // 0x20 (about 0.77 x VCC) is the reset value
    lcd.set_charge_pump(true).expect("set_charge_pump");                        // Enable the charge pump, (enabled) → ()
                                                                                // 0x8D 0x14; takes effect with the display on

    // Addressing modes.
    lcd.set_addressing_mode(AddressingMode::Horizontal).expect("set_addressing_mode");// Select addressing mode, (mode) → ()
                                                                                // show() then streams the frame in one transfer
    lcd.show().expect("show");                                                  // Update display, () → ()
    lcd.set_addressing_mode(AddressingMode::Page).expect("set_addressing_mode");// Select addressing mode, (mode) → ()

    // Hardware scrolling (RAM access is refused while a scroll is active).
    lcd.set_scroll_horizontal(true, 0, 7, 7).expect("set_scroll_horizontal");   // Set horizontal scroll, (right, start_page, end_page, interval 0..7) → ()
                                                                                // interval index 7 = 2 frames per step
    lcd.scroll_start().expect("scroll_start");                                  // Start scrolling, () → ()
    sleep(2000);
    lcd.scroll_stop().expect("scroll_stop");                                    // Stop scrolling, () → ()
                                                                                // deactivates and rewrites the RAM from the framebuffer
    lcd.set_scroll_area(0, 64).expect("set_scroll_area");                       // Set vertical scroll area, (top_fixed 0..63, scroll_rows 1..64) → ()
    lcd.set_scroll_vertical(true, 0, 7, 7, 1).expect("set_scroll_vertical");    // Set diagonal scroll, (right, start_page, end_page, interval 0..7, rows 1..63) → ()
    lcd.scroll_start().expect("scroll_start");                                  // Start scrolling, () → ()
    sleep(2000);
    lcd.scroll_stop().expect("scroll_stop");                                    // Stop scrolling, () → ()

    // Raw commands.
    lcd.send_command(&[0xE3]).expect("send_command");                           // Send raw command stream, (bytes) → ()
                                                                                // NOP

    // Power.
    lcd.set_display(false).expect("set_display");                               // Display on/off, (on) → ()
                                                                                // RAM and settings are kept
    sleep(1000);
    lcd.set_display(true).expect("set_display");                                // Display on/off, (on) → ()
    lcd.sleep().expect("sleep");                                                // Enter sleep, () → ()
                                                                                // display off; switch VCC off afterwards
    sleep(1000);
    lcd.wake().expect("wake");                                                  // Leave sleep, () → ()
                                                                                // display on and a 100 ms wait (tAF)
    println!("lit {}", lit);
}
