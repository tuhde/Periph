#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::io_expander::Pcf8574Minimal;
use embedded_hal::digital::{OutputPin, InputPin};

esp_app_desc!();

const ADDR: u8 = 0x20;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let chip = Pcf8574Minimal::new(i2c, ADDR).expect("init PCF8574"); // Create PCF8574 driver, (i2c, ADDR=0x20) → Result

    let mut p0 = chip.pin(0);                                          // Get pin proxy, (n) → ExPin
    let mut p4 = chip.pin(4);                                          // Get pin proxy, (n) → ExPin

    use embedded_hal::digital::{OutputPin, InputPin};
    p0.set_low().expect("set_low");                                    // Drive low, () → Result<(), E>

    loop {
        delay.delay_ms(200);
    }
}
