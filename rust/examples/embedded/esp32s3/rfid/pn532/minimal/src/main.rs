//! Minimal example for the PN532 — print the UID of a card once per second. (ESP32-S3: SDA GPIO1, SCL GPIO2).
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
#[allow(unused_imports)]
use esp_println::{print, println};
use periph::chips::rfid::Pn532Minimal;

esp_app_desc!();

const ADDR: u8 = 0x24;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let bus = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    #[allow(unused_mut)]
    let mut delay = Delay::new();
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { delay.delay_ms($n) }; }
    let addr = ADDR;

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
