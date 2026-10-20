//! UC1611s demo example: EA DOGXL240-7 (240 x 128, 3.3 V only) over I2C on a Raspberry Pi (Linux host).
//!
//! Wiring: SDA=GPIO2, SCL=GPIO3 (/dev/i2c-1), VDD = 3V3; module pins BM0 = D13 = VDD, CD = A2 = A3 = VSS -> command 0x38 / data 0x39.
//! Capacitors: 4 x 4.7 uF/5 V (VA0, VA1, VB0, VB1 pairs), 470 nF/25 V on VLCD; pull-ups 2-4.7 k to 3.3 V. Demo: a bouncing ball with partial page updates, a contrast sweep, inverse flashes, hardware scroll, a second RAM screen and sleep.
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

    // --- Draw the frame once ---
    // A one-pixel border around the whole 240 x 128 panel; the ball bounces inside it.
    lcd.clear();                                                              // Clear framebuffer, ()
    lcd.draw_rect(0, 0, 240, 128, true, false);                               // Draw rectangle, (x, y, w, h, on, fill)
    lcd.show().expect("show");                                                // Send framebuffer to the display, () → ()
    
    // --- Bounce an 8 x 8 ball with partial page updates ---
    // Only the page rows the ball touches are re-sent, so the animation runs at about
    // 20 frames per second even at 400 kHz, instead of the 8 of a full-frame show().
    let (mut bx, mut by, mut dx, mut dy) = (20i32, 12i32, 3i32, 2i32);
    for _frame in 0..200 {
        let oldy = by;
        lcd.draw_rect(bx, by, 8, 8, false, true);                             // Draw rectangle, (x, y, w, h, on, fill)
        bx += dx;
        by += dy;
        if bx < 2 || bx > 229 { dx = -dx; bx += 2 * dx; }
        if by < 2 || by > 118 { dy = -dy; by += 2 * dy; }
        lcd.draw_rect(bx, by, 8, 8, true, true);                              // Draw rectangle, (x, y, w, h, on, fill)
        for page in (oldy.min(by) / 8)..=((oldy.max(by) + 7) / 8) {
            lcd.show_page(page as u8).expect("show_page");                    // Send one framebuffer page, (page 0..15) → ()
        }
        sleep(Duration::from_millis(20));
    }
    
    // --- Contrast sweep around the working point ---
    // The contrast is set once in normal use; sweeping PM shows the usable range of the panel.
    for pm in (0x70..=0x8Fu8).rev().step_by(2).chain((0x70..=0x8Fu8).step_by(2)) {
        lcd.set_contrast(pm).expect("set_contrast");                          // Set contrast, (value 0..255) → ()
        sleep(Duration::from_millis(40));
    }
    
    // --- Flash the inverse display twice ---
    for _ in 0..2 {
        lcd.set_invert(true).expect("set_invert");                            // Invert display, (on) → ()
        sleep(Duration::from_millis(400));
        lcd.set_invert(false).expect("set_invert");                           // Invert display, (on) → ()
        sleep(Duration::from_millis(400));
    }
    
    // --- Hardware scroll ---
    // The image moves up in the controller, no data is re-sent; a blank band enters from the bottom
    // because RAM rows 128..159 are empty.
    for line in (0..=32u8).chain((0..=32u8).rev()) {
        lcd.set_scroll_line(line).expect("set_scroll_line");                  // Scroll the image up, (line 0..159 - 2 * fixed) → ()
        sleep(Duration::from_millis(40));
    }
    
    // --- Second screen in RAM ---
    // The chip holds four black/white screens; draw a cross into screen 1 and switch to it.
    lcd.set_write_pattern(1);                                                 // Select RAM screen to write, (pattern 0..3)
    lcd.fill(false);                                                          // Fill framebuffer, (on)
    lcd.draw_line(0, 0, 239, 127, true);                                      // Draw line, (x0, y0, x1, y1, on)
    lcd.draw_line(0, 127, 239, 0, true);                                      // Draw line, (x0, y0, x1, y1, on)
    lcd.show().expect("show");                                                // Send framebuffer to the display, () → ()
    lcd.set_display_pattern(1).expect("set_display_pattern");                 // Select displayed RAM screen, (pattern 0..3) → ()
    sleep(Duration::from_millis(2000));
    lcd.set_display_pattern(0).expect("set_display_pattern");                 // Select displayed RAM screen, (pattern 0..3) → ()
    lcd.set_write_pattern(0);                                                 // Select RAM screen to write, (pattern 0..3)
    sleep(Duration::from_millis(1500));
    
    // --- Power down ---
    // Sleep stops the charge pump and the drivers (below 1 uA); RAM and registers are kept.
    lcd.sleep().expect("sleep");                                              // Enter sleep, () → ()
    println!("done (I2C retries {}, unrecovered {})", lcd.retry_count(), lcd.error_count());
}
