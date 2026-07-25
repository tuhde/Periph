#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::magnetometer::As5600Minimal;

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

    let mut chip = As5600Minimal::new(i2c, ADDR).expect("init AS5600");

    loop {
        let a = chip.angle().expect("angle");              // Read absolute angle, () → f32 degrees
        let r = chip.angle_raw().expect("angle_raw");      // Read scaled angle count, () → u16 0-4095
        println!("angle={:.2}°  raw={}", a, r);
        delay.delay_ms(1000);
    }
}
