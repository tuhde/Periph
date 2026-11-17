use linux_embedded_hal::I2cdev;
use periph::chips::led::{HT16K33Minimal, ROWS};
use std::thread::sleep;
use std::time::Duration;

fn sleep_ms(ms: u64) { sleep(Duration::from_millis(ms)); }

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x70);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");

    let mut matrix = HT16K33Minimal::new(dev, ADDR).expect("init HT16K33"); // Create HT16K33 driver, (i2c, addr=0x70)

    for row in 0..ROWS {
        matrix.set_pixel(0, row, true); // Set one LED in the shadow RAM, (com 0–7, row 0–15, on) → bool
    }
    matrix.show().expect("show"); // Write the shadow RAM to the chip, () → ()
    sleep_ms(1000);
}
