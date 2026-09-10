#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::pressure::{Lps28dfwFull, ODR_100_HZ, AVG_128, FS_MODE_2};

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

    let mut chip = match Lps28dfwFull::new(i2c, TEST_ADDR) {
        Ok(c) => c,
        Err(_) => {
            println!("FAIL init: could not reach LPS28DFW at 0x{:02X}", TEST_ADDR);
            println!("===DONE: 0 passed, 1 failed===");
            loop {}
        }
    };

    let cid_ok = chip.chip_id().map(|v| v == 0xB4).unwrap_or(false);
    check_true!(cid_ok, "chip_id", passed, failed);

    let t_ok = chip.read_temperature().map(|t| t >= -40.0 && t <= 85.0).unwrap_or(false);
    check_true!(t_ok, "temperature_range", passed, failed);

    let p_ok = chip.read_pressure().map(|p| p >= 260.0 && p <= 1260.0).unwrap_or(false);
    check_true!(p_ok, "pressure_range", passed, failed);

    let cfg_ok = chip.configure(ODR_100_HZ, AVG_128, FS_MODE_2, false, 1).is_ok();
    check_true!(cfg_ok, "configure", passed, failed);

    let alt_ok = chip.altitude(1013.25).map(|a| a >= -500.0 && a <= 9000.0).unwrap_or(false);
    check_true!(alt_ok, "altitude", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}