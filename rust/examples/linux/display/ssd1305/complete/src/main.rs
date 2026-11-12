//! SSD1305 complete example: 1.3" 128 x 64 OLED module (SSD1305, RAM columns 4..131) over I2C on a Raspberry Pi (Linux host).
//!
//! Wiring: SDA=GPIO2, SCL=GPIO3 (/dev/i2c-1), VCC = 3V3 (OLED supply VCC 7..15 V from the module, no charge pump); address 0x3C (SA0 = low) or 0x3D (SA0 = high). Complete: every Full method - drawing, contrast, invert, flip, scroll, drive tuning, reads, sleep.
use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::display::ssd1305::{AddressingMode, Ssd1305Full};

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
    let mut lcd = Ssd1305Full::new(dev, addr, Delay, 128, 64, 4).expect("init SSD1305"); // Create SSD1305 driver, (i2c, addr=0x3C, delay, width=128, height=64, column_offset=4)

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
                                                                                // segment drive current; reset value is 0x80
    sleep(800);
    lcd.set_contrast(0x80).expect("set_contrast");                              // Set contrast, (value 0..255) → ()
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
    lcd.set_clock(1, 7).expect("set_clock");                                    // Set clock, (divide 1..16, osc 0..15) → ()
                                                                                // divide ratio 1 at the reset oscillator setting
    lcd.set_precharge(2, 2).expect("set_precharge");                            // Set pre-charge, (phase1 1..15, phase2 1..15) → ()
                                                                                // pre-charge and discharge periods in display clocks
    lcd.set_com_config(true, false).expect("set_com_config");                          // Set COM pin configuration, (alternative, remap) → ()
                                                                                // alternative is the reset value and right for 128 x 64 modules
    lcd.set_vcomh(0x34).expect("set_vcomh");                                    // Set VCOMH deselect level, (value 0x00..0x3C) → ()
                                                                                // 0x34 (about 0.77 x VCC) is the reset value
    lcd.set_dim_mode(0x40, 0x40).expect("set_dim_mode");                        // Configure dim levels, (contrast 0..255, brightness 0..255) → ()
                                                                                // used while dim mode is on
    lcd.set_dim(true).expect("set_dim");                                        // Dim mode, (on) → ()
                                                                                // 0xAC display on in dim mode
    sleep(1000);
    lcd.set_dim(false).expect("set_dim");                                       // Dim mode, (on) → ()
    lcd.set_brightness(0x80).expect("set_brightness");                          // Set colour-bank brightness, (value 0..255) → ()
                                                                                // no visible effect in monochrome mode
    lcd.set_area_color(false, false).expect("set_area_color");                  // Set area-colour mode, (enable, low_power) → ()
                                                                                // keep disabled on monochrome panels
    lcd.set_lut(49, 63, 63, 63).expect("set_lut");                              // Set pulse widths, (bank0, a, b, c each 31..63) → ()
                                                                                // reset values
    lcd.set_bank_colors([0; 4], Some([0; 4])).expect("set_bank_colors");        // Assign bank colours, (banks_1_16, banks_17_32) → ()
                                                                                // all banks colour A
    lcd.set_addressing_mode(AddressingMode::Horizontal).expect("set_addressing_mode");// Select addressing mode, (mode) → ()
                                                                                // show() then streams the frame in one transfer
    lcd.show().expect("show");                                                  // Update display, () → ()
    lcd.set_addressing_mode(AddressingMode::Page).expect("set_addressing_mode");// Select addressing mode, (mode) → ()

    // Hardware scrolling.
    lcd.set_scroll_horizontal(true, 0, 7, 0, 1).expect("set_scroll_horizontal");// Set horizontal scroll, (right, start_page, end_page, interval 0..6, columns) → ()
                                                                                // interval index 0 = 6 frames per step
    lcd.scroll_start().expect("scroll_start");                                  // Start scrolling, () → ()
    sleep(2000);
    lcd.scroll_stop().expect("scroll_stop");                                    // Stop scrolling, () → ()
                                                                                // deactivates and rewrites the RAM from the framebuffer
    lcd.set_scroll_area(0, 64).expect("set_scroll_area");                       // Set vertical scroll area, (top_fixed 0..63, scroll_rows 1..64) → ()
    lcd.set_scroll_vertical(true, 0, 7, 0, 1, 1).expect("set_scroll_vertical"); // Set vertical+horizontal scroll, (right, start_page, end_page, interval, columns, rows 1..63) → ()
    lcd.scroll_start().expect("scroll_start");                                  // Start scrolling, () → ()
    sleep(2000);
    lcd.scroll_stop().expect("scroll_stop");                                    // Stop scrolling, () → ()

    // Reads (I2C only) and raw commands.
    let status = lcd.read_status().expect("read_status");                       // Read status, () → u8
                                                                                // bit 6 = 1 when the display is off
    let mut ram = [0u8; 8];
    lcd.read_page(0, 2, &mut ram).expect("read_page");                          // Read display RAM, (page 0..7, column, buf) → ()
                                                                                // sets the address and discards the dummy byte
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
    println!("status 0x{:02X}, lit {}, ram {:02X?}", status, lit, ram);
}
