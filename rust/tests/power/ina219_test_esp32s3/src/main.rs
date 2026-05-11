#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::power::Ina219Full;

esp_app_desc!();

const TEST_ADDR: u8 = 0x40;

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

    let mut passed = 0i32;
    let mut failed = 0i32;

    let mut chip = match Ina219Full::new(i2c, TEST_ADDR, 0.1, 2.0) {
        Ok(c) => c,
        Err(_) => {
            println!("FAIL init: could not reach INA219 at 0x{:02X}", TEST_ADDR);
            println!("===DONE: 0 passed, 1 failed===");
            loop {}
        }
    };

    let v_ok = chip.voltage().map(|v| v >= 0.0 && v <= 40.0).unwrap_or(false);
    check_true!(v_ok, "voltage_range", passed, failed);

    let sv_ok = chip.shunt_voltage().map(|v| v >= -0.320 && v <= 0.320).unwrap_or(false);
    check_true!(sv_ok, "shunt_voltage_range", passed, failed);

    let i_ok = chip.current().map(|v| v >= -2.0 && v <= 2.0).unwrap_or(false);
    check_true!(i_ok, "current_range", passed, failed);

    let p_ok = chip.power().map(|v| v >= 0.0 && v <= 80.0).unwrap_or(false);
    check_true!(p_ok, "power_range", passed, failed);

    check_true!(chip.conversion_ready().is_ok(), "conversion_ready_ok", passed, failed);
    check_true!(chip.overflow().is_ok(), "overflow_ok", passed, failed);

    chip.configure(1, 3, 0x03, 0x03, 7).ok();
    check_true!(chip.voltage().unwrap() >= 0.0, "after_configure_voltage_ok", passed, failed);

    let sd_ok = chip.shutdown().and_then(|_| chip.wake()).is_ok();
    check_true!(sd_ok, "shutdown_wake", passed, failed);

    check_true!(chip.reset().is_ok(), "reset", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}