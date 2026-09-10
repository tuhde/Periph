use linux_embedded_hal::I2cdev;
use periph::chips::pressure::Bmp581Minimal;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x46);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut bmp = Bmp581Minimal::new(dev, addr, false).expect("init BMP581"); // Create BMP581 driver, (i2c, addr=0x46)

    for _ in 0..5 {
        let p = bmp.pressure().expect("read pressure");             // Read pressure, () → f32 Pa
        let t = bmp.temperature().expect("read temperature");       // Read temperature, () → f32 °C
        println!("{:.2} C, {:.1} Pa", t, p);
        std::thread::sleep(std::time::Duration::from_secs(1));
    }
}