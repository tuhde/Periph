//! Minimal example for the Si1133 — print the UV and white counts once per second.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::light::Si1133Minimal;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x55);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create Si1133 driver, (i2c, addr=0x55) → Si1133Minimal
    let mut chip = Si1133Minimal::new(dev, addr);
    // Check PART_ID, reset, UV + white channels, (delay) → Result<()>
    chip.init(&mut delay).expect("init");

    loop {
        // Forced measurement, (delay) → Result<Reading {uv counts, white counts}>
        let m = chip.read(&mut delay).expect("read");
        println!("UV {}, white {}", m.uv, m.white);
        sleep_ms!(1000);
    }
}
