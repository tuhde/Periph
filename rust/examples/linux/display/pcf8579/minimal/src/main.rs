use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::display::Pcf8579Minimal;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x3C);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut lcd = Pcf8579Minimal::new(dev, addr, 0, 16, false, &mut Delay).expect("init PCF8579"); // Create PCF8579 driver, (i2c, addr=0x3C, subaddress=0 0–15, rows=16 8/16/24/32, mixed=false, delay)

    lcd.clear().expect("clear"); // Clear display RAM, () → ()
    lcd.set_pixel(0, 0, true).expect("pixel"); // Set one pixel, (x 0–39, y 0–15, on) → ()
    lcd.set_pixel(39, 15, true).expect("pixel"); // Set opposite corner, (x 0–39, y 0–15, on) → ()
    lcd.write_columns(4, 0, &[0xFF, 0x81, 0x81, 0xFF]).expect("columns"); // Write column bytes, (x 0–39, bank 0–1, data bytes) → ()
}
