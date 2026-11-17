use linux_embedded_hal::I2cdev;
use periph::chips::power::{Ltc4151Minimal};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x6A);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Ltc4151Minimal::new(dev, addr, 0.02).expect("init LTC4151"); // Create LTC4151 driver, (i2c, addr, r_sense=0.02 Ω)

    loop {
        let v = chip.voltage().expect("voltage");                       // Read supply voltage, () → Result<f32> V
        let i = chip.current().expect("current");                       // Read load current, () → Result<f32> A
        let p = chip.power().expect("power");                           // Read load power, () → Result<f32> W
        println!("V={:.3} V  I={:.4} A  P={:.3} W", v, i, p);
        sleep(Duration::from_secs(1));
    }
}
