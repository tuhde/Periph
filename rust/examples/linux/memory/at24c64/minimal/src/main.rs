//! Minimal example for the AT24C64 — write and read back one byte every second.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::memory::{At24c64Minimal};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x50);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create AT24C64 driver, (i2c, addr=0x50) → Self
    let mut chip = At24c64Minimal::new(dev, addr);

    let mut counter: u8 = 0;
    loop {
        // Write a single byte, (address 0–8191, value 0–255, delay) → Result<()>
        chip.write_byte(0x0000, counter, &mut delay).expect("write_byte");
        // Read a single byte, (address 0–8191) → Result<u8>
        let value = chip.read_byte(0x0000).expect("read_byte");
        println!("Stored: {}", value);
        counter = counter.wrapping_add(1);
        sleep_ms!(1000);
    }
}
