#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::io_expander::{Pca6416aFull, Pca6416aMinimal};
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
            println!("FAIL {}: got {}, expected {}", $label, $a, $b);
            $failed += 1;
        }
    };
}

// Wiring for the loopback checks: jumper port 0 to port 1 (P0_n <-> P1_n).
#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);

    let mut passed = 0i32;
    let mut failed = 0i32;

    // --- Pca6416aMinimal ---
    let chip = match Pca6416aMinimal::new(i2c, TEST_ADDR) {
        Ok(c) => c,
        Err(_) => {
            println!("FAIL init: could not reach PCA6416A at 0x{:02X}", TEST_ADDR);
            println!("===DONE: 0 passed, 1 failed===");
            loop {}
        }
    };

    check_eq!(chip.shadow.get(), 0xFFFF, "shadow_init", passed, failed);
    check_eq!(chip.direction.get(), 0xFFFF, "direction_init", passed, failed);

    let port = chip.read_port(1).unwrap_or(0xFF);
    check_true!(port <= 0xFF, "read_port_range", passed, failed);

    chip.write_port(0, 0x55).ok();
    chip.write_port(1, 0xAA).ok();
    check_eq!(chip.shadow.get(), 0xAA55, "write_port_shadow", passed, failed);

    let mut p15 = chip.pin(15);
    p15.into_output().ok();
    p15.set_low().ok();
    check_eq!(chip.shadow.get() & 0x8000, 0, "set_low_shadow", passed, failed);
    p15.set_high().ok();
    check_eq!(chip.shadow.get() & 0x8000, 0x8000, "set_high_shadow", passed, failed);
    check_true!(p15.is_set_high().unwrap_or(false), "is_set_high_from_shadow", passed, failed);
    p15.into_input().ok();
    check_true!(p15.is_high().is_ok(), "is_high_ok", passed, failed);
    drop(p15);

    chip.write_port(0, 0xFF).ok();
    chip.write_port(1, 0xFF).ok();

    // --- Pca6416aFull ---
    let i2c2 = I2c::new(peripherals.I2C1, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO3)
        .with_scl(peripherals.GPIO4);

    let full = match Pca6416aFull::new(i2c2, TEST_ADDR) {
        Ok(c) => c,
        Err(_) => {
            println!("FAIL init full: could not reach PCA6416A at 0x{:02X}", TEST_ADDR);
            println!("===DONE: {} passed, 1 failed===", passed);
            loop {}
        }
    };

    full.set_direction(0xFF00).ok();
    check_eq!(full.read_register(0x06).unwrap_or(0xFF), 0x00, "config0_readback", passed, failed);
    check_eq!(full.read_register(0x07).unwrap_or(0), 0xFF, "config1_readback", passed, failed);
    for pattern in [0x5Au8, 0xA5, 0x00] {
        full.write_port(0, pattern).ok();
        check_eq!(full.read_port(1).unwrap_or(0xFF), pattern, "loopback", passed, failed);
    }
    full.write_all(0x00FF).ok();
    check_eq!(full.read_all().unwrap_or(0), 0xFFFF, "read_all_pair", passed, failed);
    full.write_all(0x0000).ok();

    full.configure_polarity(0xFF00).ok();
    check_eq!(full.read_register(0x05).unwrap_or(0), 0xFF, "polarity1_readback", passed, failed);
    full.configure_polarity(0x0000).ok();

    full.poll_interrupt().ok();
    full.write_port(0, 0x01).ok();
    check_eq!(full.poll_interrupt().unwrap_or(0xFFFF), 0x0100, "poll_interrupt_changed", passed, failed);

    full.reset().ok();
    check_eq!(full.read_register(0x06).unwrap_or(0), 0xFF, "reset_config0", passed, failed);
    check_eq!(full.read_register(0x07).unwrap_or(0), 0xFF, "reset_config1", passed, failed);
    check_eq!(full.read_register(0x02).unwrap_or(0), 0xFF, "reset_output0", passed, failed);
    check_eq!(full.read_register(0x03).unwrap_or(0), 0xFF, "reset_output1", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {}
}
