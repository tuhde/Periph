#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::led::{HT16K33Minimal, ROWS};

esp_app_desc!();

const ADDR: u8 = 0x70;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let delay = Delay::new();

    let mut matrix = HT16K33Minimal::new(i2c, ADDR).expect("init HT16K33"); // Create HT16K33 driver, (i2c, addr=0x70)

    for row in 0..ROWS {
        matrix.set_pixel(0, row, true); // Set one LED in the shadow RAM, (com 0–7, row 0–15, on) → bool
    }
    matrix.show().expect("show"); // Write the shadow RAM to the chip, () → ()
    delay.delay_millis(1000);
    println!("===DONE: 0 passed, 0 failed===");
    loop {}
}
