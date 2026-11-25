//! Minimal example for the Si1132 — read temperature and light every 2 s.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::light::Si1132Minimal;

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

    // Create Si1132 driver, (i2c, addr=0x60) → Si1132
    let mut chip = Si1132Minimal::new(dev, addr);
    // Verify PART_ID and start forced mode, (delay) → Result<()>
    chip.init(&mut delay).expect("init");

    loop {
        // Read UV index and ALS counts, (delay) → Result<Reading {uv_index: f32, visible: u16, infrared: u16}>
        let r = chip.read(&mut delay).expect("read");
        println!("UV {:.2}, visible {}, infrared {}", r.uv_index, r.visible, r.infrared);
        sleep_ms!(1000);
    }
}
