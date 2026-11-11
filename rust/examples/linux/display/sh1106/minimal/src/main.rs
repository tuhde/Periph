//! SH1106 minimal example: 1.3" 128 x 64 OLED module (SH1106, RAM columns 2..129) over I2C on a Raspberry Pi (Linux host).
//!
//! Wiring: SDA=GPIO2, SCL=GPIO3 (/dev/i2c-1), VCC = 3V3; address 0x3C (SA0 = VSS) or 0x3D (SA0 = VDD1). Minimal use: a border and a diagonal on the 128 x 64 panel.
use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::display::sh1106::Sh1106Minimal;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x3C);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut lcd = Sh1106Minimal::new(dev, addr, Delay, 128, 64, 2, true).expect("init SH1106"); // Create SH1106 driver, (i2c, addr=0x3C, delay, width=128, height=64, column_offset=2, internal_dcdc=true)

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
