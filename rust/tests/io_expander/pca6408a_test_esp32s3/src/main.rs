#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::io_expander::{Pca6408aFull, Pca6408aMinimal};
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

// Wiring for the loopback checks: jumper P0–P3 to P4–P7 (Pn <-> Pn+4).
#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);

    let mut passed = 0i32;
    let mut failed = 0i32;

    // --- Pca6408aMinimal ---
    let chip = match Pca6408aMinimal::new(i2c, TEST_ADDR) {
        Ok(c) => c,
        Err(_) => {
            println!("FAIL init: could not reach PCA6408A at 0x{:02X}", TEST_ADDR);
            println!("===DONE: 0 passed, 1 failed===");
            loop {}
        }
    };

    check_eq!(chip.shadow.get(), 0xFF, "shadow_init", passed, failed);
    check_eq!(chip.direction.get(), 0xFF, "direction_init", passed, failed);

    let port = chip.read_port().unwrap_or(0xFF);
    check_true!(port <= 0xFF, "read_port_range", passed, failed);

    chip.write_port(0x55).ok();
    check_eq!(chip.shadow.get(), 0x55, "write_port_shadow", passed, failed);

    let mut p7 = chip.pin(7);
    p7.into_output().ok();
    p7.set_low().ok();
    check_eq!(chip.shadow.get() & 0x80, 0, "set_low_shadow", passed, failed);
    p7.set_high().ok();
    check_eq!(chip.shadow.get() & 0x80, 0x80, "set_high_shadow", passed, failed);
    check_true!(p7.is_set_high().unwrap_or(false), "is_set_high_from_shadow", passed, failed);
    p7.into_input().ok();
    check_true!(p7.is_high().is_ok(), "is_high_ok", passed, failed);
    drop(p7);

    chip.write_port(0xFF).ok();

    // --- Pca6408aFull ---
    let i2c2 = I2c::new(peripherals.I2C1, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO3)
        .with_scl(peripherals.GPIO4);

    let full = match Pca6408aFull::new(i2c2, TEST_ADDR) {
        Ok(c) => c,
        Err(_) => {
            println!("FAIL init full: could not reach PCA6408A at 0x{:02X}", TEST_ADDR);
            println!("===DONE: {} passed, 1 failed===", passed);
            loop {}
        }
    };

    full.set_direction(0xF0).ok();
    check_eq!(full.read_register(0x03).unwrap_or(0), 0xF0, "config_readback", passed, failed);
    for pattern in [0x05u8, 0x0A, 0x00] {
        full.write_port(pattern).ok();
        let hi = (full.read_port().unwrap_or(0xFF) >> 4) & 0x0F;
        check_eq!(hi, pattern, "loopback", passed, failed);
    }

    full.configure_polarity(0xF0).ok();
    check_eq!(full.read_register(0x02).unwrap_or(0), 0xF0, "polarity_readback", passed, failed);
    full.configure_polarity(0x00).ok();

    full.poll_interrupt().ok();
    full.write_port(0x01).ok();
    check_eq!(full.poll_interrupt().unwrap_or(0xFF), 0x10, "poll_interrupt_changed", passed, failed);

    full.reset().ok();
    check_eq!(full.read_register(0x03).unwrap_or(0), 0xFF, "reset_config", passed, failed);
    check_eq!(full.read_register(0x01).unwrap_or(0), 0xFF, "reset_output", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {}
}
