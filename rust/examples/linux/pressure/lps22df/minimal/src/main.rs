use linux_embedded_hal::I2cdev;
use periph::chips::pressure::Lps22dfMinimal;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x5C);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut lps = Lps22dfMinimal::new(dev, addr, false).expect("init LPS22DF"); // Create LPS22DF driver, (i2c, addr=0x5C)

    for _ in 0..5 {
        let p = lps.pressure().expect("read pressure");               // Read pressure, () → f32 Pa
        let t = lps.temperature().expect("read temperature");         // Read temperature, () → f32 °C
        println!("{:.1} C, {:.0} Pa", t, p);
        std::thread::sleep(std::time::Duration::from_secs(1));
    }
}