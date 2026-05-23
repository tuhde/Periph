use linux_embedded_hal::I2cdev;
use periph::chips::magnetometer::As5600Minimal;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x36);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = As5600Minimal::new(dev, addr).expect("init AS5600");

    loop {
        let a = chip.angle().expect("angle");              // Read absolute angle, () → f32 degrees
        let r = chip.angle_raw().expect("angle_raw");      // Read scaled angle count, () → u16 0-4095
        println!("angle={:.2}°  raw={}", a, r);
        sleep(Duration::from_secs(1));
    }
}
