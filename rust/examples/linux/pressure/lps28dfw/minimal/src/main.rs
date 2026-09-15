use linux_embedded_hal::I2cdev;
use periph::chips::pressure::Lps28dfwMinimal;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x5C);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut lps = Lps28dfwMinimal::new(dev, addr).expect("init LPS28DFW"); // Create LPS28DFW driver, (i2c, addr=0x5C)

    for _ in 0..5 {
        let t = lps.read_temperature().expect("read temperature");  // Read temperature, () → f32 °C
        let p = lps.read_pressure().expect("read pressure");        // Read pressure, () → f32 hPa
        println!("{:.1} C, {:.1} hPa", t, p);
        std::thread::sleep(std::time::Duration::from_secs(1));
    }
}