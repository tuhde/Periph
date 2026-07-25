#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::adc_dac::Pcf8591Minimal;

esp_app_desc!();

const ADDR: u8 = 0x48;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let mut adc = Pcf8591Minimal::new(i2c, ADDR).expect("init PCF8591");

    loop {
        let ch0 = adc.read_channel(0).expect("read ch0");           // Read single channel, (channel=0–3) → Result<u8, E>
        let raw = adc.read_all().expect("read all");               // Read all four channels, () → Result<[u8; 4], E>
        println!("PCF8591 minimal running ch0={} all={:?}", ch0, raw);
        delay.delay_ms(1000);
    }
}
