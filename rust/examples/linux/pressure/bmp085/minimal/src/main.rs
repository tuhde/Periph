use linux_embedded_hal::I2cdev;
use periph::chips::pressure::Bmp085Minimal;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut bmp = Bmp085Minimal::new(dev, 0x77).expect("init BMP085"); // Create BMP085 driver, (connection)

    for _ in 0..5 {
        let t = bmp.temperature().expect("read temperature");      // Read temperature, () → f32 C
        let p = bmp.pressure().expect("read pressure");            // Read pressure, () → f32 Pa
        println!("{:.1} C, {:.1} Pa", t, p);
        std::thread::sleep(std::time::Duration::from_secs(1));
    }
}