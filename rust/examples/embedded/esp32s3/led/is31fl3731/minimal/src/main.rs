#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::led::is31fl3731::{Is31fl3731Minimal, COLS};

esp_app_desc!();

const ADDR: u8 = 0x74;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let delay = Delay::new();

    let mut matrix = Is31fl3731Minimal::new(i2c, ADDR).expect("init IS31FL3731"); // Create IS31FL3731 driver, (i2c, addr=0x74)

    for col in 0..COLS {
        matrix.set_pixel(0, col, 128).unwrap();                 // Set one LED's PWM brightness, (row 0–8, col 0–15, brightness 0–255) → bool
    }
    delay.delay_millis(1000);
    matrix.fill(32).unwrap();                                   // Set every LED's PWM brightness, (brightness 0–255) → ()
    delay.delay_millis(1000);
    matrix.clear().unwrap();                                    // Switch all LEDs off, () → ()
    println!("===DONE: 0 passed, 0 failed===");
    loop {}
}
