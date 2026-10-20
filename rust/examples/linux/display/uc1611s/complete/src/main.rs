//! UC1611s complete example: EA DOGXL240-7 (240 x 128, 3.3 V only) over I2C on a Raspberry Pi (Linux host).
//!
//! Wiring: SDA=GPIO2, SCL=GPIO3 (/dev/i2c-1), VDD = 3V3; module pins BM0 = D13 = VDD, CD = A2 = A3 = VSS -> command 0x38 / data 0x39.
//! Capacitors: 4 x 4.7 uF/5 V (VA0, VA1, VB0, VB1 pairs), 470 nF/25 V on VLCD; pull-ups 2-4.7 k to 3.3 V. Complete: every Full method - drawing, contrast, invert, flip, scroll, drive tuning, partial display, RAM screens, reads, sleep, reset.
use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::display::uc1611s::Uc1611sFull;
use std::{thread::sleep, time::Duration};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x38);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut lcd = Uc1611sFull::new(dev, addr, Delay, 240, 128, false, 0x8F).expect("init UC1611s"); // Create UC1611s driver, (i2c, addr=0x38, delay, width=240, height=128, top_view=false, contrast=0x8F)

    // Framebuffer drawing: everything is composed in RAM and sent with show().
    lcd.clear();                                                              // Clear framebuffer, ()
                                                                              // zeroes every pixel of the framebuffer; the panel is unchanged until show()
    lcd.fill(true);                                                           // Fill framebuffer, (on)
                                                                              // sets (or clears) every pixel
    lcd.fill(false);                                                          // Fill framebuffer, (on)
    lcd.draw_rect(0, 0, 240, 128, true, false);                               // Draw rectangle, (x, y, w, h, on, fill)
                                                                              // one-pixel outline around the whole panel
    lcd.draw_line(0, 0, 239, 127, true);                                      // Draw line, (x0, y0, x1, y1, on)
                                                                              // Bresenham line into the framebuffer
    lcd.draw_line(0, 127, 239, 0, true);                                      // Draw line, (x0, y0, x1, y1, on)
    lcd.draw_rect(20, 20, 40, 30, true, true);                                // Draw rectangle, (x, y, w, h, on, fill)
                                                                              // filled rectangle
    lcd.draw_rect(30, 28, 20, 14, false, true);                               // Draw rectangle, (x, y, w, h, on, fill)
                                                                              // on=false clears pixels: a hole in the filled rectangle
    lcd.set_pixel(120, 64, true);                                             // Set pixel, (x, y, on)
    let lit = lcd.get_pixel(120, 64);                                         // Read framebuffer pixel, (x, y) → bool
                                                                              // reads the framebuffer, not the panel
    let icon = [0x3C, 0x42, 0x81, 0xA5, 0x81, 0xA5, 0x99, 0x42, 0x3C, 0x00, 0x7E, 0x81, 0x81, 0x81, 0x7E, 0x00];
    lcd.blit(180, 16, &icon, 2).expect("blit");                               // Copy bitmap, (x, y multiple of 8, columns, height_pages) → ()
                                                                              // two page rows of 8 columns, bit 0 = top pixel
    lcd.show().expect("show");                                                // Send framebuffer to the display, () → ()
                                                                              // per page: page + column address, then 240 data bytes in 32-byte chunks
    sleep(Duration::from_millis(2000));
    lcd.show_page(2).expect("show_page");                                     // Send one framebuffer page, (page 0..15) → ()
                                                                              // cheaper partial update of 8 rows
    
    // Contrast and display modes.
    lcd.set_contrast(0x70).expect("set_contrast");                            // Set contrast, (value 0..255) → ()
                                                                              // writes the potentiometer PM; lower = lighter
    sleep(Duration::from_millis(800));
    lcd.set_contrast(0x8F).expect("set_contrast");                            // Set contrast, (value 0..255) → ()
                                                                              // 0x8F is the DOG example value
    lcd.set_invert(true).expect("set_invert");                                // Invert display, (on) → ()
                                                                              // bit-wise inversion of what is displayed; RAM unchanged
    sleep(Duration::from_millis(800));
    lcd.set_invert(false).expect("set_invert");                               // Invert display, (on) → ()
    lcd.set_all_on(true).expect("set_all_on");                                // Light all pixels, (on) → ()
                                                                              // test mode, RAM unchanged
    sleep(Duration::from_millis(800));
    lcd.set_all_on(false).expect("set_all_on");                               // Light all pixels, (on) → ()
    lcd.set_flip(false, true).expect("set_flip");                             // Mirror display, (mirror_x, mirror_y) → ()
                                                                              // MY = 1: rows mirrored at once; MX is applied to RAM writes
    sleep(Duration::from_millis(800));
    lcd.set_flip(true, false).expect("set_flip");                             // Mirror display, (mirror_x, mirror_y) → ()
                                                                              // back to the bottom-view default
    lcd.show().expect("show");                                                // Send framebuffer to the display, () → ()
    
    // Hardware scroll with a fixed header.
    lcd.set_fixed_lines(2).expect("set_fixed_lines");                         // Set fixed lines, (lines 0..15) → ()
                                                                              // the top 2 x 2 = 4 rows stay put
    for line in (0..=32).step_by(4) {
        lcd.set_scroll_line(line).expect("set_scroll_line");                  // Scroll the image up, (line 0..159 - 2 * fixed) → ()
                                                                              // rows enter from the bottom as the image moves up
        sleep(Duration::from_millis(120));
    }
    lcd.set_scroll_line(0).expect("set_scroll_line");                         // Scroll the image up, (line 0..159 - 2 * fixed) → ()
    lcd.set_fixed_lines(0).expect("set_fixed_lines");                         // Set fixed lines, (lines 0..15) → ()
    
    // LCD drive tuning (the DOG defaults are already sent at init).
    lcd.set_bias_ratio(2).expect("set_bias_ratio");                           // Set bias ratio, (ratio 0..3) → ()
                                                                              // 0 = 1/5, 1 = 1/10, 2 = 1/11 (reset), 3 = 1/12
    lcd.set_temperature_compensation(1).expect("set_temperature_compensation");  // Set temperature compensation, (curve 0..3) → ()
                                                                              // 1 = -0.10 %/degC, the DOG example value
    lcd.set_line_rate(3).expect("set_line_rate");                             // Set line rate, (rate 0..3) → ()
                                                                              // 3 = 9.4 kilo-lines per second in On/Off mode
    lcd.set_pump(true).expect("set_pump");                                    // Select VLCD source, (internal) → ()
                                                                              // internal 11x charge pump
    lcd.set_panel_loading(true).expect("set_panel_loading");                  // Set panel loading, (high) → ()
                                                                              // 33..55 nF panel capacitance
    lcd.set_nline_inversion(0, false).expect("set_nline_inversion");          // Set N-line inversion, (lines 0..63, xor) → ()
                                                                              // 0 = off
    lcd.set_isolation_clock(1, 0).expect("set_isolation_clock");              // Set isolation clock, (front 0..15, back 0..15) → ()
                                                                              // the reset values
    
    // Partial display: scan only the middle 64 rows.
    lcd.set_partial_display(true, 32, Some(95)).expect("set_partial_display");  // Set partial display, (on, start, end) → ()
                                                                              // rows outside 32..95 are not scanned and save power
    sleep(Duration::from_millis(1500));
    lcd.set_partial_display(false, 0, None).expect("set_partial_display");    // Set partial display, (on, start, end) → ()
                                                                              // back to the full panel
    
    // Four RAM screens: draw into screen 1, then switch to it.
    lcd.set_write_pattern(1);                                                 // Select RAM screen to write, (pattern 0..3)
                                                                              // show() now writes RAM screen 1
    lcd.fill(false);                                                          // Fill framebuffer, (on)
    lcd.draw_rect(40, 30, 160, 68, true, true);                               // Draw rectangle, (x, y, w, h, on, fill)
    lcd.show().expect("show");                                                // Send framebuffer to the display, () → ()
                                                                              // screen 0 is still displayed
    lcd.set_display_pattern(1).expect("set_display_pattern");                 // Select displayed RAM screen, (pattern 0..3) → ()
                                                                              // the display now shows RAM screen 1
    sleep(Duration::from_millis(1500));
    lcd.set_display_pattern(0).expect("set_display_pattern");                 // Select displayed RAM screen, (pattern 0..3) → ()
    lcd.set_write_pattern(0);                                                 // Select RAM screen to write, (pattern 0..3)
    
    // Read back over I2C.
    let status = lcd.read_status().expect("read_status");                     // Read status bytes, () → [u8; 3]
                                                                              // NOP first, then Status1 = Ver MX MY WA DE WS MD MS, Status3 high nibble = product code 1
    println!("status {:02X} {:02X} {:02X}", status[0], status[1], status[2]);
    let mut ram = [0u8; 8];
    lcd.read_ram(0, 0, &mut ram).expect("read_ram");                          // Read display RAM, (page, column, buf) → ()
                                                                              // the dummy byte after the address change is discarded
    println!("ram {:02X?}", ram);
    
    // Sleep, raw access, reset.
    lcd.sleep().expect("sleep");                                              // Enter sleep, () → ()
                                                                              // drivers, pump and clock stop; registers and RAM are kept
    sleep(Duration::from_millis(1000));
    lcd.wake().expect("wake");                                                // Leave sleep, () → ()
                                                                              // polls Status1.DE while the chip restores VLCD
    lcd.send_command(&[0xE3]).expect("send_command");                         // Send raw command bytes, (bytes) → ()
                                                                              // 0xE3 = NOP
    lcd.send_data(&[0u8; 8]).expect("send_data");                             // Send raw RAM data, (bytes) → ()
                                                                              // written at the current RAM address
    lcd.reset().expect("reset");                                              // System Reset and reconfigure, () → ()
                                                                              // registers back to the DOG settings with the cached contrast and mapping; RAM is kept
    let _ = lit;
    println!("done (I2C retries {}, unrecovered {})", lcd.retry_count(), lcd.error_count());
}
