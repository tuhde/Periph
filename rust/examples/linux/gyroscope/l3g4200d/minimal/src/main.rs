use linux_embedded_hal::I2cdev;
use periph::chips::gyroscope::L3g4200dMinimal;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x68);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut gyro = L3g4200dMinimal::new(dev, addr, false).expect("init L3G4200D"); // Create L3G4200D driver, (i2c, addr=0x68, spi=false)

    for _ in 0..10 {
        let (x, y, z) = gyro.angular_rate().expect("angular_rate");                  // Read X/Y/Z angular rate, () → (f32, f32, f32) rad/s
        println!("X={:.3} Y={:.3} Z={:.3} rad/s", x, y, z);
        std::thread::sleep(std::time::Duration::from_millis(100));
    }
}
