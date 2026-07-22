use linux_embedded_hal::I2cdev;
use periph::chips::comms::Rda5807mMinimal;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x10);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut fm = Rda5807mMinimal::new(dev, addr, 100.0, 8).expect("init RDA5807M");

    loop {
        if let Some(freq) = fm.seek(true).expect("seek") {    // Seek to next station, (up=true) → Option<f32> MHz
            println!("{:.2} MHz", freq);
        }
        sleep(Duration::from_secs(3));
    }
}
