#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::pressure::Bmp180Minimal;

esp_app_desc!();

const ADDR: u8 = 0x77;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let mut bmp = Bmp180Minimal::new(i2c, 0x77).expect("init BMP180"); // Create BMP180 driver, (connection)

    for _ in 0..5 {
        let t = bmp.temperature().expect("read temperature");      // Read temperature, () → f32 C
        let p = bmp.pressure().expect("read pressure");            // Read pressure, () → f32 hPa
        println!("{:.1} C, {:.1} hPa", t, p);
        delay.delay_ms(1000);
    }
    loop {}
}
