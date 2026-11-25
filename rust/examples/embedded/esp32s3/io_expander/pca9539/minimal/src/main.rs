#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::io_expander::Pca9539Minimal;
use embedded_hal::digital::{InputPin, OutputPin};

esp_app_desc!();

const ADDR: u8 = 0x74;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let chip = Pca9539Minimal::new(i2c, ADDR).expect("init PCA9539");    // Create PCA9539 driver, (i2c, addr=0x74) → Result

    let mut p0 = chip.pin(0);                                              // Get pin proxy, (n) → ExPin
    let mut p15 = chip.pin(15);                                            // Get pin proxy, (n) → ExPin
    p15.into_output().expect("into_output");                               // Make P17 an output, () → Result<(), E>

    loop {
        let port  = chip.read_port(1).expect("read_port");               // Read the 8 pins of port 1, (port) → Result<u8, E>
        let level = p0.is_high().expect("is_high");                        // Read actual level, () → Result<bool, E>
        if level { p15.set_high().expect("set_high"); }                     // Set high, () → Result<(), E>
        else     { p15.set_low().expect("set_low");   }                     // Drive low, () → Result<(), E>
        println!("PORT1=0x{:02X}  P0={}", port, level as u8);
        delay.delay_ms(200);
    }
}
