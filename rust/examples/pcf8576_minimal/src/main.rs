use linux_embedded_hal::I2cdev;
use periph::chips::display::{Pcf8576Minimal, SEVEN_SEG};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x38);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut lcd = Pcf8576Minimal::new(dev, addr).expect("init PCF8576"); // Create PCF8576 driver, (i2c, addr=0x38)

    let digits = [1u8, 2, 3, 4];
    for (i, d) in digits.iter().enumerate() {
        let seg = SEVEN_SEG[*d as usize]; // Encode 7-segment digit, (digit 0–9) → u8
        lcd.set_digit_7seg(i as u8, seg).expect("write digit"); // Write one digit, (position 0–19, segments 0–255) → ()
    }
}
