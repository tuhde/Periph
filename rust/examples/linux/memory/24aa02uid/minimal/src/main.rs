use linux_embedded_hal::I2cdev;
use periph::chips::memory::Eeprom24Aa02UidMinimal;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x50);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Eeprom24Aa02UidMinimal::new(dev, addr);                            // Create 24AA02UID driver, (i2c, addr=0x50) → Self

    loop {
        let uid = chip.read_uid().expect("read_uid");                                   // Read 32-bit unique serial number, () → Result<[u8; 4]>
        println!("UID: {:02X}{:02X}{:02X}{:02X}", uid[0], uid[1], uid[2], uid[3]);
        sleep(Duration::from_secs(2));
    }
}
