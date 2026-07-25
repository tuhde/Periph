#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::io_expander::Pcf8575Full;
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

    let chip = Pcf8575Full::new(i2c, ADDR).expect("init PCF8575");  // Create PCF8575 full driver, (i2c, ADDR=0x20) → Result

    chip.write_port(0, 0xFF).expect("write_port 0");                // Write Port 0, (port=0, mask) → Result<(), E>
    chip.write_port(1, 0xFF).expect("write_port 1");                 // Write Port 1, (port=1, mask) → Result<(), E>

    println!("Running — buttons on P10–P17 mirror to LEDs on P00–P07");
    loop {
        let port0 = chip.read_port(0).expect("read_port port0");     // Read Port 0, (port=0) → Result<u8, E>
        let port1 = chip.read_port(1).expect("read_port port1");     // Read Port 1, (port=1) → Result<u8, E>

        let buttons = port1 & 0xFF;                                   // P10–P17 (pressed = 0)
        let led_bits = !buttons;                                     // invert: pressed → LED on (0)
        chip.write_port(0, led_bits).expect("write_port 0");        // Write Port 0, (port=0, mask) → Result<(), E>

        println!(
            "P0=0x{:02X}  P1=0x{:02X}  buttons={}  LEDs={}",
            port0, port1,
            String::from_iter((0..8).map(|i| if (buttons >> (7 - i)) & 1 != 0 { '.' } else { 'X' })),
            String::from_iter((0..8).map(|i| if (led_bits >> (7 - i)) & 1 != 0 { ' ' } else { '*' }))
        );
        delay.delay_ms(200);
    }
}
