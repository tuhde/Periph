#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::environmental::Aht21Full;

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

    let mut chip = match Aht21Full::new(i2c, TEST_ADDR, &mut delay) {
        Ok(c) => c,
        Err(_) => {
            println!("FAIL init: could not reach AHT21 at 0x{:02X}", TEST_ADDR);
            println!("===DONE: 0 passed, 1 failed===");
            loop {}
        }
    };

    check_true!(chip.is_calibrated().unwrap_or(false), "is_calibrated", passed, failed);
    check_true!(!chip.is_busy().unwrap_or(true), "not_busy_at_idle", passed, failed);

    let (t, h) = chip.read(&mut delay).unwrap_or((-999.0, -999.0));
    check_true!(t >= -40.0 && t <= 120.0, "temperature_range", passed, failed);
    check_true!(h >= 0.0 && h <= 100.0, "humidity_range", passed, failed);

    let tr = chip.read_temperature(&mut delay).unwrap_or(-999.0);
    check_true!(tr >= -40.0 && tr <= 120.0, "read_temperature_range", passed, failed);

    let hr = chip.read_humidity(&mut delay).unwrap_or(-999.0);
    check_true!(hr >= 0.0 && hr <= 100.0, "read_humidity_range", passed, failed);

    let (tc, hc, crc_ok) = chip.read_with_crc(&mut delay).unwrap_or((-999.0, -999.0, false));
    check_true!(crc_ok, "crc_ok", passed, failed);
    check_true!(tc >= -40.0 && tc <= 120.0, "crc_temperature_range", passed, failed);
    check_true!(hc >= 0.0 && hc <= 100.0, "crc_humidity_range", passed, failed);

    chip.soft_reset(&mut delay).ok();
    delay.delay_ms(50);
    check_true!(chip.is_calibrated().unwrap_or(false), "calibrated_after_reset", passed, failed);

    let (t2, h2) = chip.read(&mut delay).unwrap_or((-999.0, -999.0));
    check_true!(t2 >= -40.0 && t2 <= 120.0, "read_after_reset_temperature", passed, failed);
    check_true!(h2 >= 0.0 && h2 <= 100.0, "read_after_reset_humidity", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}
