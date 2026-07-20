use linux_embedded_hal::I2cdev;
use linux_embedded_hal::Delay;
use periph::chips::imu::Mpu6050Minimal;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x68);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut chip = Mpu6050Minimal::new(dev, addr, &mut delay).expect("init MPU6050"); // Create MPU6050 driver, (i2c, addr, delay) → Result

    loop {
        let (ax, ay, az) = chip.accel().expect("accel"); // Read 3-axis acceleration, () → (f32, f32, f32) m/s²
        let (gx, gy, gz) = chip.gyro().expect("gyro");   // Read 3-axis angular rate, () → (f32, f32, f32) rad/s
        println!("accel: {:.2} {:.2} {:.2}  gyro: {:.2} {:.2} {:.2}",
                 ax, ay, az, gx, gy, gz);
        sleep(Duration::from_millis(100));
    }
}
