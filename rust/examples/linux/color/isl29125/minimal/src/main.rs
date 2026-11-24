//! Minimal example for the ISL29125 — print red, green and blue counts.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::color::{Isl29125Minimal, ISL29125_I2C_ADDRESS};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(ISL29125_I2C_ADDRESS);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create ISL29125 driver, (i2c, addr=0x44) → Isl29125Minimal
    let mut chip = Isl29125Minimal::new(dev, addr);
    // Check DEVICE_ID and start RGB conversion, () → Result<(), Isl29125Error>
    chip.init().expect("init");

    loop {
        // Read the three channels, (delay) → Result<Isl29125Counts>
        let c = chip.read(&mut delay).expect("read");
        println!("R {} G {} B {}", c.red, c.green, c.blue);
        sleep_ms!(1000);
    }
}
