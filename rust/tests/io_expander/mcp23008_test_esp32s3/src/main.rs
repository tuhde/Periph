#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::io_expander::{Mcp23008Full, Mcp23008Minimal};
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

// Wiring for the loopback checks: jumper GP0–GP3 to GP4–GP7 (GPn <-> GPn+4).
#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);

    let mut passed = 0i32;
    let mut failed = 0i32;

    // --- Mcp23008Minimal ---
    let chip = match Mcp23008Minimal::new(i2c, TEST_ADDR) {
        Ok(c) => c,
        Err(_) => {
            println!("FAIL init: could not reach MCP23008 at 0x{:02X}", TEST_ADDR);
            println!("===DONE: 0 passed, 1 failed===");
            loop {}
        }
    };

    check_eq!(chip.shadow.get(), 0, "shadow_init", passed, failed);
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

    chip.write_port(0x00).ok();

    // --- Mcp23008Full ---
    let i2c2 = I2c::new(peripherals.I2C1, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO3)
        .with_scl(peripherals.GPIO4);

    let full = match Mcp23008Full::new(i2c2, TEST_ADDR) {
        Ok(c) => c,
        Err(_) => {
            println!("FAIL init full: could not reach MCP23008 at 0x{:02X}", TEST_ADDR);
            println!("===DONE: {} passed, 1 failed===", passed);
            loop {}
        }
    };

    full.write_register(0x00, 0xF0).ok();
    check_eq!(full.read_register(0x00).unwrap_or(0), 0xF0, "iodir_readback", passed, failed);
    for pattern in [0x05u8, 0x0A, 0x00] {
        full.write_port(pattern).ok();
        let hi = (full.read_port().unwrap_or(0xFF) >> 4) & 0x0F;
        check_eq!(hi, pattern, "loopback", passed, failed);
    }

    full.configure_pullup(0xF0).ok();
    check_eq!(full.read_register(0x06).unwrap_or(0), 0xF0, "pullup_readback", passed, failed);
    full.configure_pullup(0x00).ok();
    full.configure_polarity(0x00).ok();

    full.enable_pin_interrupt(0x10).ok();
    let flags = full.poll_interrupt().unwrap_or(0xFF);
    check_true!(flags <= 0xFF, "poll_interrupt_range", passed, failed);
    let cap = full.read_capture().unwrap_or(0xFF);
    check_true!(cap <= 0xFF, "read_capture_range", passed, failed);

    full.reset().ok();
    check_eq!(full.read_register(0x00).unwrap_or(0), 0xFF, "reset_iodir", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {}
}
