//! Minimal example for the BNO055 — read the fused Euler angles every 100 ms.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::imu::bno055::Bno055Minimal;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x29);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create BNO055 driver, (i2c, addr=0x29, delay) → Result
    let mut imu = Bno055Minimal::new(dev, addr, &mut delay).expect("init");

    loop {
        // Read fused Euler angles, () → Result<(f32, f32, f32)> rad
        let (h, r, p) = imu.euler().expect("read euler");
        println!("heading {:.2} rad, roll {:.2}, pitch {:.2}", h, r, p);
        sleep_ms!(100);
    }
}
