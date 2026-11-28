//! Minimal example for the ST25DV — write "Hello NFC" and read it back every second.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::rfid::{St25dvMinimal};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x53);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create ST25DV driver, (i2c, delay, user_addr=0x53, system_addr=0x57) → Self
    // the chip answers on two addresses on the same bus
    let mut chip = St25dvMinimal::new(dev, Delay, addr, 0x57);

    // Read MEM_SIZE, BLK_SIZE, IC_REF, () → Result<()>
    chip.init().expect("init");
    // Write user memory, (address, data) → Result<()>
    chip.write(0x0100, b"Hello NFC").expect("write");
    let mut buf = [0u8; 9];
    loop {
        // Read user memory, (address, buf) → Result<()>
        chip.read(0x0100, &mut buf).expect("read");
        println!("Memory: {:02X?}", buf);
        sleep_ms!(1000);
    }
}
