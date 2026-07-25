#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::io_expander::Pcf8574Full;

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

    let chip = Pcf8574Full::new(i2c, ADDR).expect("init PCF8574");     // Create PCF8574 full driver, (i2c, ADDR) → Result

    // --- Configure output/input nibbles ---
    // P0–P3 as outputs (LEDs, active-low); P4–P7 as inputs (buttons, internal pull-up).
    // Writing 0xF0 keeps P4–P7 high (input mode) and drives P0–P3 low (LEDs on).
    chip.write_port(0xF0).expect("write_port");                        // Write all 8 pins, (mask) → Result<(), E>

    println!("Running — press buttons on P4–P7 to mirror to LEDs on P0–P3");

    loop {
        let led_bits = (!buttons) & 0x0F;
        chip.write_port(0xF0 | led_bits).expect("write_port");         // Write all 8 pins, (mask) → Result<(), E>

        let btn_str: String = (0..4).map(|i| if (buttons >> i) & 1 == 0 { 'X' } else { '.' }).collect();
        let led_str: String = (0..4).map(|i| if (led_bits >> i) & 1 == 1 { '*' } else { ' ' }).collect();
        println!("port=0x{:02X}  buttons=[{}]  LEDs=[{}]", port, btn_str, led_str);

        delay.delay_ms(200);
    }
}
