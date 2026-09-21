use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::imu::Mpu9250Minimal;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x68);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut imu = Mpu9250Minimal::new(dev, addr, &mut delay).expect("init");

    loop {
        let (ax, ay, az) = imu.accel().expect("read accel");
        let (gx, gy, gz) = imu.gyro().expect("read gyro");
        println!("accel: {:.2} {:.2} {:.2}  gyro: {:.2} {:.2} {:.2}", ax, ay, az, gx, gy, gz);
        std::thread::sleep(std::time::Duration::from_millis(100));
    }
}