#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::other::Mpr121Minimal;

esp_app_desc!();

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());                     // Init ESP HAL, () → Peripherals
    let mut delay = Delay::new();                                                     // Create delay provider, () → Delay
    let i2c = I2c::new(peripherals.I2C0, Config::default())                           // Create I²C bus, (I2C0, config=default 100kHz) → I2c
        .unwrap()
        .with_sda(peripherals.GPIO8)
        .with_scl(peripherals.GPIO9);

    let mut chip = Mpr121Minimal::new(i2c, 0x5A, &mut delay).unwrap();                // Construct MPR121 Minimal, (i2c, addr=0x5A, delay) → Result<Mpr121Minimal, _>

    loop {
        let t = chip.touched().unwrap();                                               // Read 12-bit touch bitmask, () → Result<u16, _> bitmask
        println!("touched=0x{:03X}", t);
        delay.delay_ms(1000);
    }
}
