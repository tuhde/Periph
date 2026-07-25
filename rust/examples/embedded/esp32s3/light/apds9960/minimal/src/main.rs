#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::light::Apds9960Minimal;

esp_app_desc!();

const ADDR: u8 = 0x39;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let mut chip = Apds9960Minimal::new(i2c, ADDR, &mut delay).expect("init APDS9960");

    loop {
        let (c, r, g, b) = chip.color().expect("color");
        println!("C={} R={} G={} B={}", c, r, g, b);
        delay.delay_ms(1000);
    }
}
