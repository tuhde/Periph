use linux_embedded_hal::I2cdev;
use periph::chips::memory::Mb85rcMinimal;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x50);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Mb85rcMinimal::new(dev, addr);                                                            // Create MB85RC driver, (i2c, addr=0x50) → Self

    let mut counter: u8 = 0;
    loop {
        chip.write_byte(0x0000, counter).expect("write_byte");                                    // Write a single byte, (address 0–32767, value 0–255) → Result<()>
        let value = chip.read_byte(0x0000).expect("read_byte");                                   // Read a single byte, (address 0–32767) → Result<u8>
        println!("Stored: {}", value);
        counter = counter.wrapping_add(1);
        sleep(Duration::from_secs(1));
    }
}
