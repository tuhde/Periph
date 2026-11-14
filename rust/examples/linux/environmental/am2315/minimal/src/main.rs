//! Minimal example for the AM2315 — read temperature and humidity (the driver spaces reads 2 s apart).

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::environmental::AM2315Minimal;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x5C);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create AM2315 driver, (i2c, addr=0x5C) → AM2315
    let mut chip = AM2315Minimal::new(dev, addr);

    loop {
        // Read temperature and humidity, (delay) → Result<(f32 °C, f32 %RH)>
        // (the driver waits 2 s before every transaction; the result is the previous measurement)
        let (t, rh) = chip.read(&mut delay).expect("read");
        println!("{:.1} C, {:.1} %RH", t, rh);
    }
}
