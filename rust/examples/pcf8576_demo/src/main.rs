use linux_embedded_hal::I2cdev;
use periph::chips::display::{Pcf8576Full, SEG_7SEG};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x38);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Pcf8576Full::new(dev, addr).expect("init PCF8576");

    chip.clear().expect("clear");

    let mut counter = 9999;
    while counter >= 0 {
        let digits = [
            (counter / 1000) % 10,
            (counter / 100) % 10,
            (counter / 10) % 10,
            counter % 10,
        ];
        println!("{}", counter);
        let data: [u8; 4] = [
            SEG_7SEG[digits[0] as usize],
            SEG_7SEG[digits[1] as usize],
            SEG_7SEG[digits[2] as usize],
            SEG_7SEG[digits[3] as usize],
        ];
        chip.write_raw(0, &data).expect("write");
        counter -= 1;
        sleep(Duration::from_secs(1));
    }

    let dash: [u8; 4] = [0x49, 0x49, 0x49, 0x49];
    chip.write_raw(0, &dash).expect("dash");
    println!("done");
}