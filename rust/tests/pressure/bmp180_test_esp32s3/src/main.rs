#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::pressure::{Bmp180Full, OSS_ULP};

esp_app_desc!();

const TEST_ADDR: u8 = 0x77;

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

    let mut chip = match Bmp180Full::new(i2c, TEST_ADDR, OSS_ULP) {
        Ok(c) => c,
        Err(_) => {
            println!("FAIL init: could not reach BMP180 at 0x{:02X}", TEST_ADDR);
            println!("===DONE: 0 passed, 1 failed===");
            loop {}
        }
    };

    let cid_ok = chip.chip_id().map(|v| v == 0x55).unwrap_or(false);
    check_true!(cid_ok, "chip_id", passed, failed);

    check_true!(chip.oversampling() == 0, "default_oss", passed, failed);

    chip.set_oversampling(2);
    check_true!(chip.oversampling() == 2, "set_oss", passed, failed);

    let alt_ok = chip.altitude(1013.25).map(|a| a >= 0.0).unwrap_or(false);
    check_true!(alt_ok, "altitude", passed, failed);

    let slp_ok = chip.sea_level_pressure(0.0).map(|s| s >= 900.0 && s <= 1100.0).unwrap_or(false);
    check_true!(slp_ok, "sea_level_pressure", passed, failed);

    let reset_ok = chip.reset().is_ok();
    check_true!(reset_ok, "reset", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}
