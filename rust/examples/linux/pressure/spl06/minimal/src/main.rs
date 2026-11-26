//! Minimal example for the SPL06-007 — read temperature and pressure every second.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::pressure::Spl06Minimal;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x77);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create SPL06 driver, (i2c, addr=0x77) → Spl06Minimal
    let mut chip = Spl06Minimal::new(dev, addr);
    // Check ID, read coefficients, apply defaults, (delay) → Result<()>
    chip.init(&mut delay).expect("init");

    loop {
        // Measure temperature and pressure, (delay) → Result<Spl06Measurement {temperature_c °C, pressure_pa Pa}>
        let m = chip.read(&mut delay).expect("read");
        println!("{:.2} Pa, {:.2} C", m.pressure_pa, m.temperature_c);
        sleep_ms!(1000);
    }
}
