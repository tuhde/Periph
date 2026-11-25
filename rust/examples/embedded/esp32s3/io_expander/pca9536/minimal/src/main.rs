#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::io_expander::Pca9536Minimal;
use embedded_hal::digital::{InputPin, OutputPin};

esp_app_desc!();

const ADDR: u8 = 0x41;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let chip = Pca9536Minimal::new(i2c, ADDR).expect("init PCA9536");    // Create PCA9536 driver, (i2c, addr=0x41) → Result

    let mut p2 = chip.pin(2);                                              // Get pin proxy, (n) → ExPin
    let mut p0 = chip.pin(0);                                              // Get pin proxy, (n) → ExPin
    p0.into_output().expect("into_output");                                // Make P0 an output, () → Result<(), E>

    loop {
        let port  = chip.read_port().expect("read_port");                  // Read all 4 pins, () → Result<u8, E>
        let level = p2.is_high().expect("is_high");                        // Read actual level, () → Result<bool, E>
        if level { p0.set_high().expect("set_high"); }                     // Set high, () → Result<(), E>
        else     { p0.set_low().expect("set_low");   }                     // Drive low, () → Result<(), E>
        println!("PORT=0x{:X}  P2={}", port, level as u8);
        delay.delay_ms(200);
    }
}
