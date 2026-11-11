#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::io_expander::{Pca9536Full, Pca9536Minimal};
use embedded_hal::digital::{InputPin, OutputPin, StatefulOutputPin};

esp_app_desc!();

const TEST_ADDR: u8 = 0x41;

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

// Wiring for the loopback checks: jumper P0–P1 to P2–P3 (Pn <-> Pn+2).
#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);

    let mut passed = 0i32;
    let mut failed = 0i32;

    // --- Pca9536Minimal ---
    let chip = match Pca9536Minimal::new(i2c, TEST_ADDR) {
        Ok(c) => c,
        Err(_) => {
            println!("FAIL init: could not reach PCA9536 at 0x{:02X}", TEST_ADDR);
            println!("===DONE: 0 passed, 1 failed===");
            loop {}
        }
    };

    check_eq!(chip.shadow.get(), 0xFF, "shadow_init", passed, failed);
    check_eq!(chip.direction.get(), 0xFF, "direction_init", passed, failed);

    let port = chip.read_port().unwrap_or(0xFF);
    check_true!(port <= 0x0F, "read_port_range", passed, failed);

    chip.write_port(0x05).ok();
    check_eq!(chip.shadow.get() & 0x0F, 0x05, "write_port_shadow", passed, failed);

    let mut p3 = chip.pin(3);
    p3.into_output().ok();
    p3.set_low().ok();
    check_eq!(chip.shadow.get() & 0x08, 0, "set_low_shadow", passed, failed);
    p3.set_high().ok();
    check_eq!(chip.shadow.get() & 0x08, 0x08, "set_high_shadow", passed, failed);
    check_true!(p3.is_set_high().unwrap_or(false), "is_set_high_from_shadow", passed, failed);
    p3.into_input().ok();
    check_true!(p3.is_high().is_ok(), "is_high_ok", passed, failed);
    drop(p3);

    chip.write_port(0x0F).ok();

    // --- Pca9536Full ---
    let i2c2 = I2c::new(peripherals.I2C1, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO3)
        .with_scl(peripherals.GPIO4);

    let full = match Pca9536Full::new(i2c2, TEST_ADDR) {
        Ok(c) => c,
        Err(_) => {
            println!("FAIL init full: could not reach PCA9536 at 0x{:02X}", TEST_ADDR);
            println!("===DONE: {} passed, 1 failed===", passed);
            loop {}
        }
    };

    full.set_direction(0x0C).ok();
    check_eq!(full.read_register(0x03).unwrap_or(0) & 0x0F, 0x0C, "config_readback", passed, failed);
    for pattern in [0x01u8, 0x02, 0x00] {
        full.write_port(pattern).ok();
        let hi = (full.read_port().unwrap_or(0xFF) >> 2) & 0x03;
        check_eq!(hi, pattern, "loopback", passed, failed);
    }

    full.configure_polarity(0x0C).ok();
    check_eq!(full.read_register(0x02).unwrap_or(0) & 0x0F, 0x0C, "polarity_readback", passed, failed);
    full.configure_polarity(0x00).ok();

    full.poll_changes().ok();
    full.write_port(0x01).ok();
    check_eq!(full.poll_changes().unwrap_or(0xFF), 0x04, "poll_changes_changed", passed, failed);

    full.reset().ok();
    check_eq!(full.read_register(0x03).unwrap_or(0) & 0x0F, 0x0F, "reset_config", passed, failed);
    check_eq!(full.read_register(0x01).unwrap_or(0) & 0x0F, 0x0F, "reset_output", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {}
}
