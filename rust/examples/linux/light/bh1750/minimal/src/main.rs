//! Minimal example for the BH1750 — measure the illuminance once per second.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::light::BH1750Minimal;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x23);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create BH1750 driver, (i2c, addr=0x23) → BH1750
    let mut chip = BH1750Minimal::new(dev, addr);
    // Power on, () → Result<()>
    chip.init().expect("init");

    loop {
        // Measure illuminance, (delay) → Result<f32 lx>
        let lux = chip.read(&mut delay).expect("read");
        println!("{:.1} lx", lux);
        sleep_ms!(1000);
    }
}
