//! SSD1305 demo example: 1.3" 128 x 64 OLED module (SSD1305, RAM columns 4..131) over I2C on a Raspberry Pi (Linux host).
//!
//! Wiring: SDA=GPIO2, SCL=GPIO3 (/dev/i2c-1), VCC = 3V3 (OLED supply VCC 7..15 V from the module, no charge pump); address 0x3C (SA0 = low) or 0x3D (SA0 = high). Demo: a bouncing ball inside a border, a contrast fade, inverse flashes, hardware scroll and sleep.
use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::display::ssd1305::Ssd1305Full;

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

    // --- Draw the border once ---
    // A one-pixel border around the 128 x 64 panel; the ball bounces inside it.
    lcd.clear();                                                                // Clear framebuffer, ()
    lcd.draw_rect(0, 0, 128, 64, true, false);                                  // Draw rectangle, (x, y, w, h, on, fill)
    lcd.show().expect("show");                                                  // Send framebuffer to the display, () → ()

    // --- Bounce a 6 x 6 ball at about 20 frames per second ---
    // The ball is erased and redrawn in the framebuffer, then the frame is sent with show().
    let (mut bx, mut by, mut dx, mut dy) = (20i32, 12i32, 3i32, 2i32);
    for _frame in 0..200 {
        lcd.draw_rect(bx, by, 6, 6, false, true);                               // Draw rectangle, (x, y, w, h, on, fill)
        bx += dx;
        by += dy;
        if bx < 2 || bx > 120 {
            dx = -dx;
            bx += 2 * dx;
        }
        if by < 2 || by > 56 {
            dy = -dy;
            by += 2 * dy;
        }
        lcd.draw_rect(bx, by, 6, 6, true, true);                                // Draw rectangle, (x, y, w, h, on, fill)
        lcd.show().expect("show");                                              // Send framebuffer to the display, () → ()
        sleep(50);
    }

    // --- Fade the contrast down and back up ---
    // 255 -> 0 -> 255 in steps of 5 shows the full range of the segment drive current.
    for v in (0..=255u8).rev().step_by(5).chain((0..=255u8).step_by(5)) {
        lcd.set_contrast(v).expect("set_contrast");                             // Set contrast, (value 0..255) → ()
        sleep(20);
    }

    // --- Flash the inverse display twice ---
    for _ in 0..2 {
        lcd.set_invert(true).expect("set_invert");                              // Invert display, (on) → ()
        sleep(400);
        lcd.set_invert(false).expect("set_invert");                             // Invert display, (on) → ()
        sleep(400);
    }

    // --- Dim mode ---
    // Dim mode lowers contrast and colour-bank brightness to the levels stored with set_dim_mode.
    lcd.set_dim_mode(0x10, 0x10).expect("set_dim_mode");                        // Configure dim levels, (contrast 0..255, brightness 0..255) → ()
    lcd.set_dim(true).expect("set_dim");                                        // Dim mode, (on) → ()
    sleep(2000);
    lcd.set_dim(false).expect("set_dim");                                       // Dim mode, (on) → ()

    // --- Hardware scroll ---
    // The scroll engine moves all eight pages right in the controller without re-sending any data;
    // stopping rewrites the RAM from the framebuffer as the datasheet requires.
    lcd.set_scroll_horizontal(true, 0, 7, 0, 1).expect("set_scroll_horizontal");// Set horizontal scroll, (right, start_page, end_page, interval 0..6, columns) → ()
    lcd.scroll_start().expect("scroll_start");                                  // Start scrolling, () → ()
    sleep(5000);
    lcd.scroll_stop().expect("scroll_stop");                                    // Stop scrolling, () → ()

    // --- Power down ---
    // Sleep turns the display off (switch VCC off afterwards); RAM and registers are kept.
    lcd.sleep().expect("sleep");                                                // Enter sleep, () → ()
}
