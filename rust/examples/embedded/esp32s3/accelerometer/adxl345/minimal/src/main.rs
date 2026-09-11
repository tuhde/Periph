#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::accelerometer::Adxl345Minimal;

esp_app_desc!();

const ADDR: u8 = 0x53;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let delay = Delay::new();

    let mut accel = Adxl345Minimal::new(i2c, ADDR, false).expect("init ADXL345"); // Create ADXL345 driver, (i2c, addr=0x53, spi=false)

    loop {
        let (x, y, z) = accel.read().expect("read acceleration"); // Read 3-axis acceleration, () → (f32, f32, f32) g
        println!("x={:.3} y={:.3} z={:.3} g", x, y, z);
        delay.delay_ms(100);
    }
}
