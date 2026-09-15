use linux_embedded_hal::I2cdev;
use periph::chips::pressure::Lps33hwMinimal;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x5C);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Lps33hwMinimal::new(dev, addr).expect("init LPS33HW"); // Create LPS33HW driver, (i2c, addr=0x5C)

    for _ in 0..5 {
        let t = chip.temperature().expect("read temperature");      // Read temperature, () → f32 °C
        let p = chip.pressure().expect("read pressure");            // Read pressure, () → f32 Pa
        println!("{:.2} C, {:.1} Pa", t, p);
        std::thread::sleep(std::time::Duration::from_secs(1));
    }
}