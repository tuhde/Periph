//! Minimal example for the MPL3115A2 — read pressure and temperature every 2 s.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::pressure::MPL3115A2Minimal;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x60);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create MPL3115A2 driver, (i2c, addr=0x60) → MPL3115A2
    let mut chip = MPL3115A2Minimal::new(dev, addr);
    // Check the chip and apply the defaults, (delay) → Result<()>
    chip.init(&mut delay).expect("init");

    loop {
        // Read pressure and temperature, (delay) → Result<(f32 Pa, f32 °C)>
        let (p, t) = chip.read(&mut delay).expect("read");
        println!("{:.2} Pa, {:.2} C", p, t);
        sleep_ms!(2000);
    }
}
