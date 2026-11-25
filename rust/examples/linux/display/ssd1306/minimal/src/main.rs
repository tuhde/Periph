//! SSD1306 minimal example: 0.96" 128 x 64 OLED module (SSD1306) over I2C on a Raspberry Pi (Linux host).
//!
//! Wiring: SDA=GPIO2, SCL=GPIO3 (/dev/i2c-1), VCC = 3V3 (the module generates the OLED supply with the SSD1306 charge pump); address 0x3C (SA0 = low) or 0x3D (SA0 = high). Minimal use: a border and a diagonal on the 128 x 64 panel.
use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::display::ssd1306::Ssd1306Minimal;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x3C);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut lcd = Ssd1306Minimal::new(dev, addr, Delay, 128, 64, false).expect("init SSD1306"); // Create SSD1306 driver, (i2c, addr=0x3C, delay, width=128, height=64, external_vcc=false)

    lcd.clear();                                                                // Clear framebuffer, ()
    for x in 0..128 {
        lcd.set_pixel(x, 0, true);                                              // Set pixel, (x 0..127, y 0..63, on)
        lcd.set_pixel(x, 63, true);                                             // Set pixel, (x 0..127, y 0..63, on)
        lcd.set_pixel(x, x / 2, true);                                          // Set pixel, (x 0..127, y 0..63, on)
    }
    for y in 0..64 {
        lcd.set_pixel(0, y, true);                                              // Set pixel, (x 0..127, y 0..63, on)
        lcd.set_pixel(127, y, true);                                            // Set pixel, (x 0..127, y 0..63, on)
    }
    lcd.show().expect("show");                                                  // Send framebuffer to the display, () → ()
}
