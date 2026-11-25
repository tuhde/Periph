//! Minimal example for the SAA1064 — count up on the four digits. (ESP32-S3: SDA GPIO1, SCL GPIO2).
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::led::SAA1064Minimal;

esp_app_desc!();

const ADDR: u8 = 0x38;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    #[allow(unused_mut)]
    let mut delay = Delay::new();
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { delay.delay_ms($n) }; }

    // Create SAA1064 driver, (i2c, addr=0x38) → SAA1064
    let mut chip = SAA1064Minimal::new(i2c, ADDR).expect("new");

    let mut n: u16 = 0;
    loop {
        // Show a right-aligned number, (value 0–9999) → Result<bool>
        chip.show_number(n).expect("show_number");
        n = (n + 1) % 10000;
        sleep_ms!(100);
    }
}
