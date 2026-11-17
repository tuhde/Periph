#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::power::{Ltc4151Minimal};

esp_app_desc!();

const ADDR: u8 = 0x6A;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let mut chip = Ltc4151Minimal::new(i2c, ADDR, 0.02).expect("init LTC4151"); // Create LTC4151 driver, (i2c, addr, r_sense=0.02 Ω)

    loop {
        let v = chip.voltage().expect("voltage");                       // Read supply voltage, () → Result<f32> V
        let i = chip.current().expect("current");                       // Read load current, () → Result<f32> A
        let p = chip.power().expect("power");                           // Read load power, () → Result<f32> W
        println!("V={:.3} V  I={:.4} A  P={:.3} W", v, i, p);
        delay.delay_ms(1000);
    }
}
