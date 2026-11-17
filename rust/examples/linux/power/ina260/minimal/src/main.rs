use linux_embedded_hal::I2cdev;
use periph::chips::power::Ina260Minimal;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x40);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Ina260Minimal::new(dev, addr).expect("init INA260");

    loop {
        let v = chip.voltage().expect("voltage");
        let i = chip.current().expect("current");
        let p = chip.power().expect("power");
        println!("V={:.3}V  I={:.6}A  P={:.6}W", v, i, p);
        sleep(Duration::from_secs(1));
    }
}
