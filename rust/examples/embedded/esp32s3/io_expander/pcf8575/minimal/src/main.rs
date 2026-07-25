#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::io_expander::Pcf8575Minimal;
use embedded_hal::digital::OutputPin;

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

    let chip = Pcf8575Minimal::new(i2c, ADDR).expect("init PCF8575"); // Create PCF8575 driver, (i2c, ADDR=0x20) → Result

    let mut p0 = chip.pin(0);                                          // Get pin proxy, (n=0) → ExPin
    let mut p8 = chip.pin(8);                                          // Get pin proxy, (n=8) → ExPin

    p0.set_low().expect("set_low");                                   // Drive low, () → Result<(), E>

    loop {
        let port0 = chip.read_port(0).expect("read_port port0");       // Read Port 0, (port=0) → Result<u8, E>
        let port1 = chip.read_port(1).expect("read_port port1");       // Read Port 1, (port=1) → Result<u8, E>
        if (port1 & 0x01) != 0 { p0.set_high().expect("set_high"); }  // Set high (quasi-input), () → Result<(), E>
        else                  { p0.set_low().expect("set_low");  }    // Drive low, () → Result<(), E>
        println!("P0=0x{:02X}  P1=0x{:02X}", port0, port1);
        delay.delay_ms(200);
    }
}
