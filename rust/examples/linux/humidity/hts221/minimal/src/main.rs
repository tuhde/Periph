//! Minimal example for the HTS221 — read temperature and humidity every 2 s.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::humidity::HTS221Minimal;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x5F);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create HTS221 driver, (i2c, addr=0x5F) → HTS221
    let mut chip = HTS221Minimal::new(dev, addr);
    // Read the calibration and start one-shot mode, () → Result<()>
    chip.init().expect("init");

    loop {
        // Read temperature and humidity, (delay) → Result<(f32 °C, f32 %rH)>
        let (t, rh) = chip.read(&mut delay).expect("read");
        println!("{:.2} C, {:.1} %rH", t, rh);
        sleep_ms!(2000);
    }
}
