//! Minimal example for the SAA1064 — count up on the four digits.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::led::SAA1064Minimal;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x38);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create SAA1064 driver, (i2c, addr=0x38) → SAA1064
    let mut chip = SAA1064Minimal::new(dev, addr).expect("new");

    let mut n: u16 = 0;
    loop {
        // Show a right-aligned number, (value 0–9999) → Result<bool>
        chip.show_number(n).expect("show_number");
        n = (n + 1) % 10000;
        sleep_ms!(100);
    }
}
