use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::power::Lc709203fMinimal;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x0B);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut chip = Lc709203fMinimal::new(dev, addr, 1000, 1, &mut delay).expect("init LC709203F"); // Create LC709203F driver, (i2c, addr, capacity_mah, profile, delay)

    loop {
        let v = chip.voltage().expect("voltage");
        let p = chip.percent().expect("percent");
        let t = chip.temperature().expect("temperature");
        println!("V={:.3} V  SOC={:.1} %  T={:.2} C", v, p, t);   // Read V / % / °C, () → Result<f32>
        sleep(Duration::from_millis(5000));
    }
}
