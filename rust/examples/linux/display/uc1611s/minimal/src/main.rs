//! UC1611s minimal example: EA DOGXL240-7 (240 x 128, 3.3 V only) over I2C on a Raspberry Pi (Linux host).
//!
//! Wiring: SDA=GPIO2, SCL=GPIO3 (/dev/i2c-1), VDD = 3V3; module pins BM0 = D13 = VDD, CD = A2 = A3 = VSS -> command 0x38 / data 0x39.
//! Capacitors: 4 x 4.7 uF/5 V (VA0, VA1, VB0, VB1 pairs), 470 nF/25 V on VLCD; pull-ups 2-4.7 k to 3.3 V. Minimal use: a border and a diagonal on the 240 x 128 panel.
use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::display::uc1611s::Uc1611sMinimal;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x38);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut lcd = Uc1611sMinimal::new(dev, addr, Delay, 240, 128).expect("init UC1611s"); // Create UC1611s driver, (i2c, addr=0x38, delay, width=240, height=128)

    lcd.clear();                                                              // Clear framebuffer, ()
    for x in 0..240 {
        lcd.set_pixel(x, 0, true);                                            // Set pixel, (x 0..239, y 0..127, on)
        lcd.set_pixel(x, 127, true);                                          // Set pixel, (x 0..239, y 0..127, on)
        lcd.set_pixel(x, x * 127 / 239, true);                                // Set pixel, (x 0..239, y 0..127, on)
    }
    for y in 0..128 {
        lcd.set_pixel(0, y, true);                                            // Set pixel, (x 0..239, y 0..127, on)
        lcd.set_pixel(239, y, true);                                          // Set pixel, (x 0..239, y 0..127, on)
    }
    lcd.show().expect("show");                                                // Send framebuffer to the display, () → ()
}
