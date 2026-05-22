#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::pressure::{Bmp280Full, OSRS_X1, OSRS_X2, OSRS_X4, MODE_FORCED, FILTER_OFF, FILTER_4, T_SB_250_MS};

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

    let mut chip = match Bmp280Full::new(i2c, TEST_ADDR, OSRS_X1, OSRS_X1, MODE_FORCED, FILTER_OFF, 0) {
        Ok(c) => c,
        Err(_) => {
            println!("FAIL init: could not reach BMP280 at 0x{:02X}", TEST_ADDR);
            println!("===DONE: 0 passed, 1 failed===");
            loop {}
        }
    };

    let cid_ok = chip.chip_id().map(|v| v == 0x58).unwrap_or(false);
    check_true!(cid_ok, "chip_id", passed, failed);

    let t_ok = chip.temperature().map(|t| t >= -20.0 && t <= 85.0).unwrap_or(false);
    check_true!(t_ok, "temperature_range", passed, failed);

    let p_ok = chip.pressure().map(|p| p >= 300.0 && p <= 1100.0).unwrap_or(false);
    check_true!(p_ok, "pressure_range", passed, failed);

    let alt_ok = chip.altitude(1013.25).map(|a| a.is_finite()).unwrap_or(false);
    check_true!(alt_ok, "altitude", passed, failed);

    let slp_ok = chip.sea_level_pressure(0.0).map(|s| s >= 300.0 && s <= 1100.0).unwrap_or(false);
    check_true!(slp_ok, "sea_level_pressure", passed, failed);

    let cfg_ok = chip.configure(OSRS_X2, OSRS_X4, MODE_FORCED, FILTER_4, T_SB_250_MS).is_ok();
    check_true!(cfg_ok, "configure", passed, failed);

    let oss_ok = chip.set_oversampling(OSRS_X1, OSRS_X1).is_ok();
    check_true!(oss_ok, "set_oversampling", passed, failed);

    let flt_ok = chip.set_filter(FILTER_OFF).is_ok();
    check_true!(flt_ok, "set_filter", passed, failed);

    let reset_ok = chip.reset().is_ok();
    check_true!(reset_ok, "reset", passed, failed);

    let post_reset_ok = chip.temperature().map(|t| t >= -20.0 && t <= 85.0).unwrap_or(false);
    check_true!(post_reset_ok, "temperature_after_reset", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}
