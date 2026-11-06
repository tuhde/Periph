#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::display::Pcf8579Minimal;

esp_app_desc!();

const ADDR: u8 = 0x3C;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    // PCF8579 supports 100 kHz at most
    let i2c = I2c::new(peripherals.I2C0, Config::default().with_frequency(esp_hal::time::Rate::from_khz(100)))
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let mut lcd = Pcf8579Minimal::new(i2c, ADDR, 0, 16, false, &mut delay).expect("init PCF8579"); // Create PCF8579 driver, (i2c, ADDR=0x3C, subaddress=0 0–15, rows=16 8/16/24/32, mixed=false, delay)

    lcd.clear().expect("clear"); // Clear display RAM, () → ()
    lcd.set_pixel(0, 0, true).expect("pixel"); // Set one pixel, (x 0–39, y 0–15, on) → ()
    lcd.set_pixel(39, 15, true).expect("pixel"); // Set opposite corner, (x 0–39, y 0–15, on) → ()
    lcd.write_columns(4, 0, &[0xFF, 0x81, 0x81, 0xFF]).expect("columns"); // Write column bytes, (x 0–39, bank 0–1, data bytes) → ()
    println!("===DONE: 0 passed, 0 failed===");
    loop {}
}
