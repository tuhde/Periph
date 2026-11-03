#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::memory::Mb85rcMinimal;

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

    let mut chip = Mb85rcMinimal::new(i2c, ADDR);                                                            // Create MB85RC driver, (i2c, ADDR=0x50) → Self

    let mut counter: u8 = 0;
    loop {
        chip.write_byte(0x0000, counter).expect("write_byte");                                    // Write a single byte, (address 0–32767, value 0–255) → Result<()>
        let value = chip.read_byte(0x0000).expect("read_byte");                                   // Read a single byte, (address 0–32767) → Result<u8>
        println!("Stored: {}", value);
        counter = counter.wrapping_add(1);
        delay.delay_ms(1000);
    }
}
