#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::memory::Eeprom24Aa02UidMinimal;

esp_app_desc!();

const ADDR: u8 = 0x50;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let mut chip = Eeprom24Aa02UidMinimal::new(i2c, ADDR);                            // Create 24AA02UID driver, (i2c, ADDR=0x50) → Self

    loop {
        let uid = chip.read_uid().expect("read_uid");                                   // Read 32-bit unique serial number, () → Result<[u8; 4]>
        println!("UID: {:02X}{:02X}{:02X}{:02X}", uid[0], uid[1], uid[2], uid[3]);
        delay.delay_ms(2000);
    }
}
