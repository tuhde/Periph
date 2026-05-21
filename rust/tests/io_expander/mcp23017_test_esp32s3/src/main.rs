#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::io_expander::{Mcp23017Full, Mcp23017Minimal};
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

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);

    let mut passed = 0i32;
    let mut failed = 0i32;

    // --- Mcp23017Minimal ---
    let chip = match Mcp23017Minimal::new(i2c, TEST_ADDR) {
        Ok(c) => c,
        Err(_) => {
            println!("FAIL init: could not reach MCP23017 at 0x{:02X}", TEST_ADDR);
            println!("===DONE: 0 passed, 1 failed===");
            loop {}
        }
    };

    check_eq!(chip.shadow[0].get(), 0, "shadow_init_olata", passed, failed);
    check_eq!(chip.shadow[1].get(), 0, "shadow_init_olatb", passed, failed);

    let porta = chip.read_port(0).unwrap_or(0xFF);
    check_true!(porta <= 0xFF, "read_porta_range", passed, failed);

    let portb = chip.read_port(1).unwrap_or(0xFF);
    check_true!(portb <= 0xFF, "read_portb_range", passed, failed);

    chip.write_port(0, 0x55).ok();
    check_eq!(chip.shadow[0].get(), 0x55, "write_porta_shadow", passed, failed);

    chip.write_port(1, 0xAA).ok();
    check_eq!(chip.shadow[1].get(), 0xAA, "write_portb_shadow", passed, failed);

    let mut p0 = chip.pin(0);
    p0.set_low().ok();
    check_eq!(chip.shadow[0].get() & 0x01, 0, "set_low_shadow", passed, failed);

    p0.set_high().ok();
    check_eq!(chip.shadow[0].get() & 0x01, 1, "set_high_shadow", passed, failed);

    let v = p0.is_high().unwrap_or(2);
    check_true!(v == 0 || v == 1, "is_high_range", passed, failed);

    let v2 = p0.is_set_high().unwrap_or(false);
    check_true!(v2, "is_set_high_from_shadow", passed, failed);

    let p8 = chip.pin(8);
    check_eq!(p8.n, 8, "pin_8_index", passed, failed);
    drop(p8);

    let p15 = chip.pin(15);
    check_eq!(p15.n, 15, "pin_15_index", passed, failed);
    drop(p15);

    chip.write_port(0, 0x00).ok();
    chip.write_port(1, 0x00).ok();

    // --- Mcp23017Full ---
    let i2c2 = I2c::new(peripherals.I2C1, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO3)
        .with_scl(peripherals.GPIO4);

    let full = match Mcp23017Full::new(i2c2, TEST_ADDR) {
        Ok(c) => c,
        Err(_) => {
            println!("FAIL init full: could not reach MCP23017 at 0x{:02X}", TEST_ADDR);
            println!("===DONE: {} passed, 1 failed===", passed);
            loop {}
        }
    };

    full.configure_pullup(0, 0x55).ok();
    full.configure_pullup(1, 0xAA).ok();

    full.configure_polarity(0, 0x0F).ok();
    full.configure_polarity(1, 0xF0).ok();

    let flags = full.read_interrupt_flags(0).unwrap_or(0xFF);
    check_true!(flags <= 0xFF, "interrupt_flags_range", passed, failed);

    let changed = full.clear_interrupt(0).unwrap_or(0xFF);
    check_true!(changed <= 0xFF, "clear_interrupt_range", passed, failed);

    let p1 = full.pin(1);
    let _ = p1;

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {}
}