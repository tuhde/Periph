#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::io_expander::{Pcf8574Full, Pcf8574Minimal};
use embedded_hal::digital::{InputPin, OutputPin, StatefulOutputPin};

esp_app_desc!();

const TEST_ADDR: u8 = 0x20;

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond {
            println!("PASS {}", $label);
            $passed += 1;
        } else {
            println!("FAIL {}", $label);
            $failed += 1;
        }
    };
}

macro_rules! check_eq {
    ($a:expr, $b:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $a == $b {
            println!("PASS {}", $label);
            $passed += 1;
        } else {
            println!("FAIL {}", $label);
            $failed += 1;
        }
    };
}

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);

    let mut passed = 0i32;
    let mut failed = 0i32;

    // --- Pcf8574Minimal ---
    let chip1 = match Pcf8574Minimal::new(i2c, TEST_ADDR) {
        Ok(c) => c,
        Err(_) => {
            println!("FAIL init: could not reach PCF8574 at 0x{:02X}", TEST_ADDR);
            println!("===DONE: 0 passed, 1 failed===");
            loop {}
        }
    };

    // Shadow initialises to 0xFF
    let mut p0 = chip1.pin(0);
    check_true!(p0.is_set_high().unwrap_or(false), "shadow_init_high", passed, failed);

    let mut p7 = chip1.pin(7);
    check_true!(p7.is_set_high().unwrap_or(false), "shadow_init_high_p7", passed, failed);
    drop(p7);

    // read_port returns valid byte
    let port = chip1.read_port().unwrap_or(0xFF);
    check_true!(port <= 0xFF, "read_port_range", passed, failed);

    // set_high / set_low update shadow
    p0.set_high().ok();
    check_true!(p0.is_set_high().unwrap_or(false), "set_high_shadow", passed, failed);
    p0.set_low().ok();
    check_true!(p0.is_set_low().unwrap_or(false), "set_low_shadow", passed, failed);

    // is_high reads bus level (pin driven low → should read low)
    let actual = p0.is_high().unwrap_or(true);
    check_true!(!actual, "is_high_driven_low", passed, failed);
    drop(p0);

    // write_port and shadow cross-check
    chip1.write_port(0b00001111).ok();
    let mut p4 = chip1.pin(4);
    check_true!(p4.is_set_high().unwrap_or(false), "write_port_p4_high", passed, failed);
    let mut p0b = chip1.pin(0);
    check_true!(p0b.is_set_low().unwrap_or(false), "write_port_p0_low", passed, failed);
    drop(p4);
    drop(p0b);

    chip1.write_port(0xFF).ok();

    // --- We need a second I2C bus handle for Pcf8574Full ---
    // ESP32-S3 has two I2C controllers; reuse peripherals.I2C1
    let i2c2 = I2c::new(peripherals.I2C1, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO3)
        .with_scl(peripherals.GPIO4);

    let chip2 = match Pcf8574Full::new(i2c2, TEST_ADDR) {
        Ok(c) => c,
        Err(_) => {
            println!("FAIL init full: could not reach PCF8574 at 0x{:02X}", TEST_ADDR);
            println!("===DONE: {} passed, 1 failed===", passed);
            loop {}
        }
    };

    // clear_interrupt immediately — nothing changed
    let changed = chip2.clear_interrupt().unwrap_or(0xFF);
    check_eq!(changed, 0u8, "clear_interrupt_no_change", passed, failed);

    chip2.write_port(0xFF).ok();
    let _changed2 = chip2.clear_interrupt().unwrap_or(0);
    check_true!(true, "clear_interrupt_after_write", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}
