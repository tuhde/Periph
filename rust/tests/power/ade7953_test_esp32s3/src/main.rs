#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::delay::Delay;
use esp_println::println;
use periph::chips::power::Ade7953Minimal;

esp_app_desc!();

const TEST_ADDR: u8 = 0x38;

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

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);

    let mut delay = Delay::new();
    let mut passed = 0i32;
    let mut failed = 0i32;

    let mut chip = match Ade7953Minimal::new(i2c, TEST_ADDR, 251.0, 30.0, &mut delay) {
        Ok(c) => c,
        Err(_) => {
            println!("FAIL init: could not reach ADE7953 at 0x{:02X}", TEST_ADDR);
            println!("===DONE: 0 passed, 1 failed===");
            loop {}
        }
    };

    check_true!(
        chip.voltage().map(|v| v >= 0.0).unwrap_or(false),
        "voltage non-negative",
        passed,
        failed
    );
    check_true!(
        chip.current().map(|v| v >= 0.0).unwrap_or(false),
        "current non-negative",
        passed,
        failed
    );
    check_true!(
        chip.active_power().map(|v| v > -1.0e6).unwrap_or(false),
        "activePower finite",
        passed,
        failed
    );
    check_true!(
        chip.active_energy().map(|v| v > -1000.0).unwrap_or(false),
        "activeEnergy finite",
        passed,
        failed
    );

    chip.reset(&mut delay).ok();
    check_true!(
        chip.voltage().map(|v| v >= 0.0).unwrap_or(false),
        "voltage after reset",
        passed,
        failed
    );

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {}
}