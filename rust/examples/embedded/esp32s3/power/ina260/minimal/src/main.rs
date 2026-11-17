#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::power::Ina260Minimal;

esp_app_desc!();

const ADDR: u8 = 0x40;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let mut chip = Ina260Minimal::new(i2c, ADDR).expect("init INA260");

    loop {
        let v = chip.voltage().expect("voltage");
        let i = chip.current().expect("current");
        let p = chip.power().expect("power");
        println!("V={:.3}V  I={:.6}A  P={:.6}W", v, i, p);
        delay.delay_ms(1000);
    }
}
