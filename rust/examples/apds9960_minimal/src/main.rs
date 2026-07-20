use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::light::Apds9960Minimal;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x39);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut chip = Apds9960Minimal::new(dev, addr, &mut delay).expect("init APDS9960");

    loop {
        let (c, r, g, b) = chip.color().expect("color");
        println!("C={} R={} G={} B={}", c, r, g, b);
        sleep(Duration::from_secs(1));
    }
}
