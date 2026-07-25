#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::environmental::Bme680Minimal;

esp_app_desc!();

const ADDR: u8 = 0x76;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let mut bme = Bme680Minimal::new(i2c, ADDR).expect("init BME680"); // Create BME680 driver, (i2c, ADDR=0x76)

    for _ in 0..5 {
        let t = bme.temperature().expect("read temperature");      // Read temperature, () → f32 °C
        let p = bme.pressure().expect("read pressure");            // Read pressure, () → f32 hPa
        let h = bme.humidity().expect("read humidity");            // Read humidity, () → f32 %RH
        let g = bme.gas_resistance().expect("read gas");           // Read gas resistance, () → f32 Ω
        println!("{:.1} C, {:.1} hPa, {:.1} %RH, {:.0} Ohm", t, p, h, g);
        delay.delay_ms(5000);
    }
    loop {}
}
