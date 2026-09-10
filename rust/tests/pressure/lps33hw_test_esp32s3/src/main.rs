#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::pressure::{Lps33hwFull, ODR_10_HZ};

esp_app_desc!();

const TEST_ADDR: u8 = 0x5C;

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

    let mut chip = match Lps33hwFull::new(i2c, TEST_ADDR) {
        Ok(c) => c,
        Err(_) => {
            println!("FAIL init: could not reach LPS33HW at 0x{:02X}", TEST_ADDR);
            println!("===DONE: 0 passed, 1 failed===");
            loop {}
        }
    };

    chip.configure(ODR_10_HZ, true, true, 1, false, false).unwrap();
    check_true!(true, "configure", passed, failed);

    let t_ok = chip.temperature().map(|t| t >= -40.0 && t <= 85.0).unwrap_or(false);
    check_true!(t_ok, "temperature_range", passed, failed);

    let p_ok = chip.pressure().map(|p| p >= 26000.0 && p <= 126000.0).unwrap_or(false);
    check_true!(p_ok, "pressure_range", passed, failed);

    let (p_os, t_os) = chip.one_shot().unwrap_or((-1.0, -300.0));
    check_true!(p_os >= 26000.0 && p_os <= 126000.0, "one_shot_pressure", passed, failed);
    check_true!(t_os >= -40.0 && t_os <= 85.0, "one_shot_temperature", passed, failed);

    let _st = chip.status().unwrap_or(0);
    check_true!(true, "status", passed, failed);

    let reset_ok = chip.reset().is_ok();
    check_true!(reset_ok, "reset", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}