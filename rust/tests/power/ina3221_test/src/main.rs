use linux_embedded_hal::I2cdev;
use periph::chips::power::{Ina3221Full, CF1, CF2, CF3, WF1, WF2, WF3, PVF, TCF, CVRF};

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

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x40);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Ina3221Full::new(dev, addr, 0.1).expect("init INA3221");

    let mut passed = 0i32;
    let mut failed = 0i32;

    check_true!(
        chip.manufacturer_id().unwrap() == 0x5449,
        "manufacturer_id",
        passed,
        failed
    );
    check_true!(
        chip.die_id().unwrap() == 0x3220,
        "die_id",
        passed,
        failed
    );

    for ch in 1..=3 {
        let label = format!("ch{}_voltage_non_negative", ch);
        check_true!(chip.voltage(ch).unwrap() >= 0.0, label, passed, failed);

        let label = format!("ch{}_shunt_voltage_finite", ch);
        check_true!(chip.shunt_voltage(ch).unwrap().abs() < 1.0, label, passed, failed);

        let label = format!("ch{}_current_finite", ch);
        check_true!(chip.current(ch).unwrap().abs() < 100.0, label, passed, failed);

        let label = format!("ch{}_power_non_negative", ch);
        check_true!(chip.power(ch).unwrap() >= 0.0, label, passed, failed);
    }

    check_true!(
        chip.conversion_ready().is_ok(),
        "conversion_ready_ok",
        passed,
        failed
    );

    chip.configure(3, 4, 4, 7).unwrap();
    check_true!(
        chip.manufacturer_id().unwrap() == 0x5449,
        "configure_mfr_id_still_valid",
        passed,
        failed
    );

    chip.set_critical_alert(1, 0.1, false).unwrap();
    chip.set_warning_alert(2, 0.05, false).unwrap();
    let flags = chip.alert_flags().unwrap();
    check_true!(flags >= 0, "alert_flags_readable", passed, failed);

    chip.enable_channel(1, false).unwrap();
    check_true!(
        !chip.channel_enabled(1).unwrap(),
        "channel_1_disabled",
        passed,
        failed
    );
    chip.enable_channel(1, true).unwrap();
    check_true!(
        chip.channel_enabled(1).unwrap(),
        "channel_1_re_enabled",
        passed,
        failed
    );

    chip.set_summation_channels(&[1, 2], 0.2).unwrap();
    let sv_sum = chip.summation_value().unwrap();
    check_true!(sv_sum.abs() < 10.0, "summation_value_finite", passed, failed);

    chip.set_power_valid_limits(5.5, 4.5).unwrap();
    check_true!(
        chip.power_valid().is_ok(),
        "power_valid_readable",
        passed,
        failed
    );

    chip.shutdown().unwrap();
    chip.wake().unwrap();
    check_true!(
        chip.voltage(1).unwrap() >= 0.0,
        "wake_voltage_non_negative",
        passed,
        failed
    );

    chip.reset().unwrap();
    check_true!(
        chip.manufacturer_id().unwrap() == 0x5449,
        "reset_mfr_id_still_valid",
        passed,
        failed
    );

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}