#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::power::Max17048Minimal;

esp_app_desc!();

const ADDR: u8 = 0x36;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let mut chip = Max17048Minimal::new(i2c, ADDR, 1).expect("init MAX17048");     // Create MAX17048 driver, (i2c, addr, cells)

    loop {
        let v = chip.voltage().expect("voltage");   // Read cell voltage, () → Result<f32>
        let p = chip.percent().expect("percent");   // Read state of charge, () → Result<f32>
        println!("V={:.3} V  SOC={:.1} %", v, p);
        delay.delay_ms(1000);
    }
}
