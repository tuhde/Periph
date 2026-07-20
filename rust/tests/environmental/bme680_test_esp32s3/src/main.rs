#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::environmental::Bme680Full;

esp_app_desc!();

const TEST_ADDR: u8 = 0x76;

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

    let mut chip = match Bme680Full::new(i2c, TEST_ADDR) {
        Ok(c) => c,
        Err(_) => {
            println!("FAIL init: could not reach BME680 at 0x{:02X}", TEST_ADDR);
            println!("===DONE: 0 passed, 1 failed===");
            loop {}
        }
    };

    let cid_ok = chip.chip_id().map(|v| v == 0x61).unwrap_or(false);
    check_true!(cid_ok, "chip_id", passed, failed);

    let t_ok = chip.temperature().map(|t| t >= -40.0 && t <= 85.0).unwrap_or(false);
    check_true!(t_ok, "temperature_range", passed, failed);

    let p_ok = chip.pressure().map(|p| p >= 300.0 && p <= 1100.0).unwrap_or(false);
    check_true!(p_ok, "pressure_range", passed, failed);

    let h_ok = chip.humidity().map(|h| h >= 0.0 && h <= 100.0).unwrap_or(false);
    check_true!(h_ok, "humidity_range", passed, failed);

    let read_ok = chip.read_all().map(|(t, p, h, _)| {
        t >= -40.0 && t <= 85.0 && p >= 300.0 && p <= 1100.0 && h >= 0.0 && h <= 100.0
    }).unwrap_or(false);
    check_true!(read_ok, "read_all", passed, failed);

    let reset_ok = chip.reset().is_ok();
    check_true!(reset_ok, "reset", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}
