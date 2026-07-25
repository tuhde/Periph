#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::comms::Rda5807mMinimal;

esp_app_desc!();

const ADDR: u8 = 0x10;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let mut fm = Rda5807mMinimal::new(i2c, ADDR, 100.0, 8).expect("init RDA5807M");

    loop {
        if let Some(freq) = fm.seek(true).expect("seek") {    // Seek to next station, (up=true) → Option<f32> MHz
            println!("{:.2} MHz", freq);
        }
        delay.delay_ms(3000);
    }
}
