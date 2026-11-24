//! Minimal example for the MS5607 — read temperature and pressure every second.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::pressure::MS5607Minimal;

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

    // Create MS5607 driver, (i2c, addr=0x77) → MS5607
    let mut chip = MS5607Minimal::new(dev, addr);
    // Reset, read PROM, verify CRC-4, (delay) → Result<()>
    chip.init(&mut delay).expect("init");

    loop {
        // Read temperature and pressure, (delay) → Result<(f32 °C, f32 Pa)>; OSR 4096, second-order compensation
        let (t, p) = chip.read(&mut delay).expect("read");
        println!("{:.2} C, {:.1} Pa", t, p);
        sleep_ms!(1000);
    }
}
