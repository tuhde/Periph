#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::power::{Ina3221Full, CF1, WF1};

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

    let mut chip = Ina3221Full::new(i2c, TEST_ADDR, 0.1);

    check_true!(
        chip.manufacturer_id().map(|v| v == 0x5449).unwrap_or(false),
        "manufacturer_id",
        passed,
        failed
    );
    check_true!(
        chip.die_id().map(|v| v == 0x3220).unwrap_or(false),
        "die_id",
        passed,
        failed
    );

    for ch in 1..=3 {
        let v_ok = chip.voltage(ch).map(|v| v >= 0.0).unwrap_or(false);
        let label = format!("ch{}_voltage_non_negative", ch);
        check_true!(v_ok, &label, passed, failed);

        let sv_ok = chip.shunt_voltage(ch).map(|v| v.abs() < 1.0).unwrap_or(false);
        let label = format!("ch{}_shunt_voltage_finite", ch);
        check_true!(sv_ok, &label, passed, failed);

        let i_ok = chip.current(ch).map(|v| v.abs() < 100.0).unwrap_or(false);
        let label = format!("ch{}_current_finite", ch);
        check_true!(i_ok, &label, passed, failed);

        let p_ok = chip.power(ch).map(|p| p >= 0.0).unwrap_or(false);
        let label = format!("ch{}_power_non_negative", ch);
        check_true!(p_ok, &label, passed, failed);
    }

    check_true!(chip.conversion_ready().is_ok(), "conversion_ready_ok", passed, failed);

    chip.configure(3, 4, 4, 7).ok();
    check_true!(
        chip.manufacturer_id().map(|v| v == 0x5449).unwrap_or(false),
        "configure_mfr_id_still_valid",
        passed,
        failed
    );

    chip.set_critical_alert(1, 0.1, false).ok();
    chip.set_warning_alert(2, 0.05, false).ok();
    let flags_ok = chip.alert_flags().map(|f| f >= 0).unwrap_or(false);
    check_true!(flags_ok, "alert_flags_readable", passed, failed);

    chip.enable_channel(1, false).ok();
    let disabled = chip.channel_enabled(1).unwrap_or(false);
    check_true!(!disabled, "channel_1_disabled", passed, failed);
    chip.enable_channel(1, true).ok();
    let re_enabled = chip.channel_enabled(1).unwrap_or(false);
    check_true!(re_enabled, "channel_1_re_enabled", passed, failed);

    chip.set_summation_channels(&[1, 2], 0.2).ok();
    let sum_ok = chip.summation_value().map(|s| s.abs() < 10.0).unwrap_or(false);
    check_true!(sum_ok, "summation_value_finite", passed, failed);

    chip.set_power_valid_limits(5.5, 4.5).ok();
    check_true!(chip.power_valid().is_ok(), "power_valid_readable", passed, failed);

    let sd_ok = chip.shutdown().and_then(|_| chip.wake()).is_ok();
    check_true!(sd_ok, "shutdown_wake", passed, failed);

    check_true!(chip.reset().is_ok(), "reset", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}