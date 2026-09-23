use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::tof::{
    Vl53l0xFull, Vl53l0xMinimal, Vl53l0xProfile, Vl53l0xVcselPeriodType, VL53L0X_SOURCE_NEW_SAMPLE_READY,
};
use std::thread::sleep;
use std::time::Duration;

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR").ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x29);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let i2c = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut minimal = Vl53l0xMinimal::new(i2c, addr, Delay).expect("init VL53L0X");
    let d = minimal.distance().expect("distance");
    check_true!(d <= 8191, "distance_in_range", passed, failed);
    let _ = minimal.range_valid();
    let (i2c, delay) = minimal.release();

    let mut full = Vl53l0xFull::new(i2c, addr, delay).expect("init VL53L0X Full");
    check_true!(full.model_id().unwrap() == 0xEE, "model_id", passed, failed);
    check_true!(full.revision_id().unwrap() > 0, "revision_id", passed, failed);
    let budget = full.timing_budget().unwrap();
    check_true!((20000..=40000).contains(&budget), "default_budget", passed, failed);

    full.distance().unwrap();
    let m = full.read_measurement().unwrap();
    check_true!(m.range_status <= 15 && m.signal_rate_mcps >= 0.0, "measurement_record", passed, failed);

    full.set_timing_budget(50000).unwrap();
    check_true!(full.timing_budget().unwrap().abs_diff(50000) < 300, "budget_roundtrip", passed, failed);
    full.set_signal_rate_limit(0.1).unwrap();
    check_true!((full.signal_rate_limit().unwrap() - 0.1).abs() < 0.01, "signal_rate_roundtrip", passed, failed);
    full.set_vcsel_pulse_period(Vl53l0xVcselPeriodType::PreRange, 18).unwrap();
    full.set_vcsel_pulse_period(Vl53l0xVcselPeriodType::FinalRange, 14).unwrap();
    check_true!(full.vcsel_pulse_period(Vl53l0xVcselPeriodType::PreRange).unwrap() == 18
        && full.vcsel_pulse_period(Vl53l0xVcselPeriodType::FinalRange).unwrap() == 14, "vcsel_roundtrip", passed, failed);
    full.set_profile(Vl53l0xProfile::Default).unwrap();
    check_true!(full.vcsel_pulse_period(Vl53l0xVcselPeriodType::PreRange).unwrap() == 14
        && full.timing_budget().unwrap().abs_diff(33000) < 300, "profile_default", passed, failed);

    let original = full.offset().unwrap();
    full.set_offset(-10.25).unwrap();
    check_true!(full.offset().unwrap() == -10.25, "offset_roundtrip", passed, failed);
    full.set_offset(original).unwrap();

    full.set_interrupt_thresholds(100, 800).unwrap();
    check_true!(full.interrupt_thresholds().unwrap() == (100, 800), "thresholds_roundtrip", passed, failed);

    // Back-to-back continuous ranging, then timed mode.
    full.start_continuous(0).unwrap();
    let ok = (0..3).all(|_| full.read_continuous().map(|r| r <= 8191).unwrap_or(false));
    check_true!(ok, "continuous_readings", passed, failed);
    full.stop_continuous().unwrap();
    sleep(Duration::from_millis(50));
    full.poll_interrupt().unwrap();
    full.start_continuous(100).unwrap();
    let ok = (0..2).all(|_| full.read_continuous().map(|r| r <= 8191).unwrap_or(false));
    check_true!(ok, "timed_readings", passed, failed);
    full.stop_continuous().unwrap();
    sleep(Duration::from_millis(150));
    full.poll_interrupt().unwrap();

    full.start_continuous(0).unwrap();
    sleep(Duration::from_millis(100));
    check_true!(full.poll_interrupt().unwrap() == VL53L0X_SOURCE_NEW_SAMPLE_READY, "poll_interrupt_new_sample", passed, failed);
    full.stop_continuous().unwrap();
    sleep(Duration::from_millis(50));
    full.poll_interrupt().unwrap();

    check_true!(full.recalibrate().is_ok(), "recalibrate", passed, failed);
    check_true!(full.distance().map(|d| d <= 8191).unwrap_or(false), "recalibrate_then_distance", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
