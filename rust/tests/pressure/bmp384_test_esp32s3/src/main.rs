#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::pressure::{Bmp384Full, MODE_FORCED, MODE_NORMAL};

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

    let mut chip = match Bmp384Full::new(i2c, TEST_ADDR, false) {
        Ok(c) => c,
        Err(_) => {
            println!("FAIL init: could not reach BMP384 at 0x{:02X}", TEST_ADDR);
            println!("===DONE: 0 passed, 1 failed===");
            loop {}
        }
    };

    check_true!(chip.inner.osr_p == 4, "default_osr_p", passed, failed);
    check_true!(chip.inner.osr_t == 1, "default_osr_t", passed, failed);
    check_true!(chip.inner.iir   == 2, "default_iir",   passed, failed);

    let t_ok = chip.temperature().map(|t| t >= -40.0 && t <= 85.0).unwrap_or(false);
    check_true!(t_ok, "temperature_range", passed, failed);

    let p_ok = chip.pressure().map(|p| p >= 300.0 && p <= 1250.0).unwrap_or(false);
    check_true!(p_ok, "pressure_range", passed, failed);

    let cfg_ok = chip.configure(2, 1, 1, 0x04).is_ok();
    check_true!(cfg_ok, "configure", passed, failed);

    let mode_ok = chip.set_mode(MODE_FORCED).is_ok();
    check_true!(mode_ok, "set_mode_forced", passed, failed);

    let _ = chip.set_mode(MODE_NORMAL);

    let ready_ok = chip.is_data_ready().is_ok();
    check_true!(ready_ok, "is_data_ready", passed, failed);

    let alt_ok = chip.altitude(1013.25).map(|a| a >= -500.0 && a <= 9000.0).unwrap_or(false);
    check_true!(alt_ok, "altitude", passed, failed);

    let reset_ok = chip.softreset().is_ok();
    check_true!(reset_ok, "softreset", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}
