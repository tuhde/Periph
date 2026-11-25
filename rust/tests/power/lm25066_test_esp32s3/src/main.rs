#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::power::Lm25066Full;

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

    let mut chip = Lm25066Full::new(i2c, TEST_ADDR, 1.0, false);

    check_true!(chip.model().map(|m| &m == b"LM25066\0").unwrap_or(false), "model_is_lm25066", passed, failed);
    check_true!(chip.manufacturer_id().map(|m| &m == b"NSC").unwrap_or(false), "manufacturer_id", passed, failed);
    check_true!(chip.voltage_in().map(|v| v >= 0.0 && v <= 24.0).unwrap_or(false), "vin_range", passed, failed);
    check_true!(chip.voltage_out().map(|v| v >= 0.0 && v <= 24.0).unwrap_or(false), "vout_range", passed, failed);
    check_true!(chip.current().map(|v| v > -1.0).unwrap_or(false), "current_readable", passed, failed);
    check_true!(chip.power().map(|v| v > -5.0).unwrap_or(false), "power_readable", passed, failed);
    check_true!(chip.temperature().map(|v| v > -40.0 && v < 150.0).unwrap_or(false), "temperature_plausible", passed, failed);
    check_true!(chip.voltage_aux().map(|v| v >= 0.0 && v <= 1.2).unwrap_or(false), "vaux_range", passed, failed);
    check_true!(chip.read_block().is_ok(), "block_readable", passed, failed);
    check_true!(chip.read_average().is_ok(), "avg_block_readable", passed, failed);
    check_true!(chip.set_averaging(4).is_ok() && chip.get_averaging().map(|v| v == 4).unwrap_or(false), "averaging", passed, failed);
    check_true!(chip.set_averaging(0).is_ok(), "averaging_reset", passed, failed);
    check_true!(chip.set_vin_uv_warn(Some(8.0)).is_ok() && chip.get_limits().map(|l| (l.vin_uv_warn.unwrap_or(0.0) - 8.0).abs() < 0.05).unwrap_or(false), "vin_uv_warn", passed, failed);
    check_true!(chip.set_vin_uv_warn(None).is_ok() && chip.get_limits().map(|l| l.vin_uv_warn.is_none()).unwrap_or(false), "vin_uv_warn_disabled", passed, failed);
    check_true!(chip.set_ot_warn(Some(110.0)).is_ok() && chip.get_limits().map(|l| (l.ot_warn.unwrap_or(0.0) - 110.0).abs() < 0.1).unwrap_or(false), "ot_warn", passed, failed);
    check_true!(chip.set_ot_warn(Some(125.0)).is_ok(), "ot_warn_restore", passed, failed);
    check_true!(chip.clear_power_peak().is_ok() && chip.power_peak().map(|v| v >= 0.0).unwrap_or(false), "peak_cleared", passed, failed);
    check_true!(chip.clear_faults().is_ok(), "clear_faults", passed, failed);
    check_true!(chip.status_word().is_ok(), "status_word_readable", passed, failed);
    check_true!(chip.diagnostic_word().is_ok(), "diagnostic_readable", passed, failed);
    check_true!(chip.capability().map(|v| v == 0xB0).unwrap_or(false), "capability", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}
