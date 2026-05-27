#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::gas::Ens160Full;

esp_app_desc!();

const TEST_ADDR: u8 = 0x52;

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

    let mut chip = match Ens160Full::new(i2c, TEST_ADDR) {
        Ok(c) => c,
        Err(_) => {
            println!("FAIL init: could not reach ENS160 at 0x{:02X}", TEST_ADDR);
            println!("===DONE: 0 passed, 1 failed===");
            loop {}
        }
    };

    let status_ok = chip.status().map(|s| s <= 3).unwrap_or(false);
    check_true!(status_ok, "status_valid_range", passed, failed);

    let t_ok = chip.read_tvoc().map(|t| t >= 0.0).unwrap_or(false);
    check_true!(t_ok, "read_tvoc", passed, failed);

    let e_ok = chip.read_eco2().map(|e| e >= 400.0).unwrap_or(false);
    check_true!(e_ok, "read_eco2", passed, failed);

    let aqi_ok = chip.read_aqi().map(|a| a >= 1 && a <= 5).unwrap_or(false);
    check_true!(aqi_ok, "read_aqi", passed, failed);

    let sleep_ok = chip.sleep().is_ok();
    check_true!(sleep_ok, "sleep", passed, failed);

    let wake_ok = chip.wake().is_ok();
    check_true!(wake_ok, "wake", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}
