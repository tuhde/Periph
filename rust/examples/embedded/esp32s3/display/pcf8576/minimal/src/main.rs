#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::display::{Pcf8576Minimal, SEVEN_SEG};

esp_app_desc!();

const ADDR: u8 = 0x38;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let mut lcd = Pcf8576Minimal::new(i2c, ADDR).expect("init PCF8576"); // Create PCF8576 driver, (i2c, ADDR=0x38)

    let digits = [1u8, 2, 3, 4];
    for (i, d) in digits.iter().enumerate() {
        let seg = SEVEN_SEG[*d as usize]; // Encode 7-segment digit, (digit 0–9) → u8
        lcd.set_digit_7seg(i as u8, seg).expect("write digit"); // Write one digit, (position 0–19, segments 0–255) → ()
    }
    loop {}
}
