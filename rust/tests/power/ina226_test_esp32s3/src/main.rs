#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::power::{Ina226Full, BOL};

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

    let mut chip = match Ina226Full::new(i2c, TEST_ADDR, 0.1, 2.0) {
        Ok(c) => c,
        Err(_) => {
            println!("FAIL init: could not reach INA226 at 0x{:02X}", TEST_ADDR);
            println!("===DONE: 0 passed, 1 failed===");
            loop {}
        }
    };

    check_true!(
        chip.manufacturer_id().map(|v| v == 0x5449).unwrap_or(false),
        "manufacturer_id",
        passed,
        failed
    );
    check_true!(
        chip.die_id().map(|v| v == 0x2260).unwrap_or(false),
        "die_id",
        passed,
        failed
    );

    chip.configure(0, 4, 4, 7).ok();

    let v_ok = chip.voltage().map(|v| v >= 0.0 && v <= 40.0).unwrap_or(false);
    check_true!(v_ok, "voltage_range", passed, failed);

    let sv_ok = chip.shunt_voltage().map(|v| v >= -0.082 && v <= 0.082).unwrap_or(false);
    check_true!(sv_ok, "shunt_voltage_range", passed, failed);

    let i_ok = chip.current().map(|v| v >= -2.0 && v <= 2.0).unwrap_or(false);
    check_true!(i_ok, "current_range", passed, failed);

    let p_ok = chip.power().map(|v| v >= 0.0 && v <= 80.0).unwrap_or(false);
    check_true!(p_ok, "power_range", passed, failed);

    check_true!(chip.conversion_ready().is_ok(), "conversion_ready_ok", passed, failed);
    check_true!(chip.overflow().is_ok(), "overflow_ok", passed, failed);

    chip.set_alert(BOL, 5.0, false, false).ok();
    let flags_ok = chip.alert_flags().map(|f| f & BOL != 0).unwrap_or(false);
    check_true!(flags_ok, "alert_flags_bol_set", passed, failed);

    let sd_ok = chip.shutdown().and_then(|_| chip.wake()).is_ok();
    check_true!(sd_ok, "shutdown_wake", passed, failed);

    check_true!(chip.reset().is_ok(), "reset", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}
