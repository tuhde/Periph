//! Minimal example for the Si1145 — proximity, UV index and light once per second.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::light::Si1145Minimal;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x60);

    let bus = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create Si1145 driver, (i2c, addr=0x60) → Si1145Minimal
    let mut sensor = Si1145Minimal::new(bus, addr);
    // Check PART_ID, HW_KEY, UV + ALS + PS1, (delay) → Result<()>
    sensor.init(&mut delay).expect("init");

    loop {
        // Forced PS + ALS, (delay) → Result<Reading {proximity, uv_index, visible, infrared}>
        let r = sensor.read(&mut delay).expect("read");
        println!("prox {}  UV {:.2}  vis {}  ir {}", r.proximity, r.uv_index, r.visible, r.infrared);
        sleep_ms!(1000);
    }
}
