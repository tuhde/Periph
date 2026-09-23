//! PCF8575 hardware-in-loop test for ESP32-S3.
//! Wiring: GPIO1 -> SDA, GPIO2 -> SCL; A0-A2 low (address 0x20).
#![no_std]
#![no_main]

use embedded_hal::digital::{InputPin, OutputPin};
use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_println::println;
use periph::chips::io_expander::Pcf8575Full;

esp_app_desc!();

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

#[main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let mut passed = 0i32;
    let mut failed = 0i32;

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    // Pcf8575Full carries the Minimal API too, so one driver covers both stages.
    let chip = Pcf8575Full::new(i2c, 0x20).expect("init PCF8575");

    check_true!(chip.write_port(0, 0xA5).is_ok(), "write_port0", passed, failed);
    check_true!(chip.write_port(1, 0xFF).is_ok(), "write_port1_inputs", passed, failed);
    check_true!(chip.read_port(1).is_ok(), "read_port1", passed, failed);

    let mut p0 = chip.pin(0);
    check_true!(p0.set_low().is_ok(), "pin0_set_low", passed, failed);
    check_true!(p0.set_high().is_ok(), "pin0_set_high", passed, failed);
    let mut p15 = chip.pin(15);
    check_true!(p15.is_high().is_ok(), "pin15_read", passed, failed);

    check_true!(chip.clear_interrupt().is_ok(), "clear_interrupt", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {}
}
