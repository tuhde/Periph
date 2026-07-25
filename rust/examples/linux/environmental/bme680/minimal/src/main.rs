use linux_embedded_hal::I2cdev;
use periph::chips::environmental::Bme680Minimal;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x76);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut bme = Bme680Minimal::new(dev, addr).expect("init BME680"); // Create BME680 driver, (i2c, addr=0x76)

    for _ in 0..5 {
        let t = bme.temperature().expect("read temperature");      // Read temperature, () → f32 °C
        let p = bme.pressure().expect("read pressure");            // Read pressure, () → f32 hPa
        let h = bme.humidity().expect("read humidity");            // Read humidity, () → f32 %RH
        let g = bme.gas_resistance().expect("read gas");           // Read gas resistance, () → f32 Ω
        println!("{:.1} C, {:.1} hPa, {:.1} %RH, {:.0} Ohm", t, p, h, g);
        std::thread::sleep(std::time::Duration::from_secs(5));
    }
}
