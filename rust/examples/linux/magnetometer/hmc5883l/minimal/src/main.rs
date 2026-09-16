use linux_embedded_hal::I2cdev;
use periph::chips::magnetometer::Hmc5883lMinimal;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x1E);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Hmc5883lMinimal::new(dev, addr).expect("init HMC5883L");

    loop {
        let (x, y, z) = chip.magnetic_field().expect("magnetic_field");  // Read magnetic field, () → (Option<f32>, Option<f32>, Option<f32>) T
        println!("X={:.6} T  Y={:.6} T  Z={:.6} T", x.unwrap_or(f32::NAN), y.unwrap_or(f32::NAN), z.unwrap_or(f32::NAN));
        sleep(Duration::from_secs(1));
    }
}