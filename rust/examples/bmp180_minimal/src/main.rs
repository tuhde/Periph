use linux_embedded_hal::I2cdev;
use periph::chips::pressure::Bmp180Minimal;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut bmp = Bmp180Minimal::new(dev, 0x77).expect("init BMP180"); // Create BMP180 driver, (transport)

    for _ in 0..5 {
        let t = bmp.temperature().expect("read temperature");      // Read temperature, () → f32 C
        let p = bmp.pressure().expect("read pressure");            // Read pressure, () → f32 hPa
        println!("{:.1} C, {:.1} hPa", t, p);
        std::thread::sleep(std::time::Duration::from_secs(1));
    }
}
