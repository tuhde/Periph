#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::io_expander::Mcp23017Minimal;
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

    let chip = Mcp23017Minimal::new(i2c, ADDR).expect("init MCP23017"); // Create MCP23017 driver, (i2c, ADDR=0x20) → Result

    let mut p0 = chip.pin(0);                                          // Get pin proxy, (n) → ExPin
    let mut p8 = chip.pin(8);                                          // Get pin proxy, (n) → ExPin

    use embedded_hal::digital::{OutputPin, InputPin};
    p0.set_low().expect("set_low");                                    // Drive low, () → Result<(), E>

    loop {
        let porta = chip.read_port(0).expect("read_port");             // Read all 8 pins, (port=0) → Result<u8, E>
        let portb = chip.read_port(1).expect("read_port");             // Read all 8 pins, (port=1) → Result<u8, E>
        let btn   = p8.is_high().expect("is_high");                    // Read actual level, () → Result<bool, E>
        if btn { p0.set_high().expect("set_high"); }                   // Set high, () → Result<(), E>
        else   { p0.set_low().expect("set_low");   }                  // Drive low, () → Result<(), E>
        println!("PORTA=0x{:02X}  PORTB=0x{:02X}  GPB0={}", porta, portb, btn as u8);
        delay.delay_ms(200);
    }
}
