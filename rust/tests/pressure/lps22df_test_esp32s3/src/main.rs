#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::pressure::Lps22dfFull;

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

    let mut chip = match Lps22dfFull::new(i2c, TEST_ADDR, false) {
        Ok(c) => c,
        Err(_) => {
            println!("FAIL init: could not reach LPS22DF at 0x{:02X}", TEST_ADDR);
            println!("===DONE: 0 passed, 1 failed===");
            loop {}
        }
    };

    let who_ok = chip.who_am_i().map(|v| v == 0xB4).unwrap_or(false);
    check_true!(who_ok, "who_am_i", passed, failed);

    let t_ok = chip.temperature().map(|t| t >= -40.0 && t <= 85.0).unwrap_or(false);
    check_true!(t_ok, "temperature_range", passed, failed);

    let p_ok = chip.pressure().map(|p| p >= 26000.0 && p <= 126000.0).unwrap_or(false);
    check_true!(p_ok, "pressure_range", passed, failed);

    let cfg_ok = chip.configure(3, 0, true, 1, true).is_ok();
    check_true!(cfg_ok, "configure", passed, failed);

    let alt_ok = chip.altitude(101325.0).map(|a| a >= -500.0 && a <= 10000.0).unwrap_or(false);
    check_true!(alt_ok, "altitude", passed, failed);

    let reset_ok = chip.software_reset().is_ok();
    check_true!(reset_ok, "software_reset", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}