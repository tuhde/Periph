//! Minimal example for the PN532 — print the UID of a card once per second.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::rfid::Pn532Minimal;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x24);

    let bus = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create PN532 driver, (i2c, addr=0x24) → Pn532Minimal
    let mut chip = Pn532Minimal::new(bus, addr);
    // Wake, check firmware, SAM normal mode, finite retries, (delay) → Result<()>
    chip.init(&mut delay).expect("init");

    loop {
        // Detect a type A card, (delay, timeout_ms=1000) → Result<Option<Vec<u8, 10>>>
        match chip.read_uid(&mut delay, 1000).expect("read_uid") {
            Some(uid) => {
                print!("UID ");
                for b in uid.iter() {
                    print!("{:02X}", b);
                }
                println!();
            }
            None => println!("no card"),
        }
        sleep_ms!(1000);
    }
}
