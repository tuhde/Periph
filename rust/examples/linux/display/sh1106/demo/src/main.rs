//! SH1106 demo example: 1.3" 128 x 64 OLED module (SH1106, RAM columns 2..129) over I2C on a Raspberry Pi (Linux host).
//!
//! Wiring: SDA=GPIO2, SCL=GPIO3 (/dev/i2c-1), VCC = 3V3; address 0x3C (SA0 = VSS) or 0x3D (SA0 = VDD1). Demo: a bouncing ball inside a border, a contrast fade, inverse flashes, hardware scroll and sleep.
use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::display::sh1106::Sh1106Full;

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
    let mut lcd = Sh1106Full::new(dev, addr, Delay, 128, 64, 2, true).expect("init SH1106"); // Create SH1106 driver, (i2c, addr=0x3C, delay, width=128, height=64, column_offset=2, internal_dcdc=true)

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

    // --- Hardware scroll ---
    // Stepping the display start line moves the image up inside the controller, no data is re-sent;
    // the picture wraps around after 64 lines.
    for line in (0..64u8).chain((0..64u8).rev()) {
        lcd.set_start_line(line).expect("set_start_line");                      // Scroll the image up, (line 0..63) → ()
        sleep(30);
    }

    // --- Power down ---
    // Sleep turns the display and the charge pump off; RAM and registers are kept.
    lcd.sleep().expect("sleep");                                                // Enter sleep, () → ()
}
