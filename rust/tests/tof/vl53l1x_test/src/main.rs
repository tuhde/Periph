use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::tof::{Vl53l1xDistanceMode, Vl53l1xFull, Vl53l1xMinimal, VL53L1X_SOURCE_NEW_SAMPLE_READY};
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
    let mut minimal = Vl53l1xMinimal::new(i2c, addr, Delay).expect("init VL53L1X");
    let d = minimal.distance().expect("distance");
    check_true!(d != u16::MAX, "distance_completes", passed, failed);
    let _ = minimal.range_valid();
    let (i2c, driver_delay) = minimal.release();

    let mut full = Vl53l1xFull::new(i2c, addr, driver_delay).expect("init VL53L1X Full");
    check_true!(full.model_id().expect("model_id") == 0xEA, "model_id", passed, failed);
    check_true!(full.module_type().expect("module_type") == 0xCC, "module_type", passed, failed);
    check_true!(full.revision_id().expect("revision_id") > 0, "revision_id", passed, failed);
    check_true!(full.timing_budget().expect("timing_budget") == 100000, "default_budget", passed, failed);
    check_true!(matches!(full.distance_mode(), Ok(Vl53l1xDistanceMode::Long)), "default_mode", passed, failed);

    full.distance().expect("distance");
    let m = full.read_measurement().expect("read_measurement");
    check_true!(m.signal_rate_mcps >= 0.0 && m.effective_spad_count >= 0.0, "measurement_record", passed, failed);

    full.set_timing_budget(50000).expect("set_timing_budget");
    check_true!(full.timing_budget().expect("timing_budget") == 50000, "budget_roundtrip", passed, failed);
    full.set_distance_mode(Vl53l1xDistanceMode::Short).expect("set_distance_mode");
    check_true!(matches!(full.distance_mode(), Ok(Vl53l1xDistanceMode::Short))
        && full.timing_budget().expect("timing_budget") == 50000, "mode_short", passed, failed);
    check_true!(full.distance().is_ok(), "short_distance", passed, failed);
    full.set_distance_mode(Vl53l1xDistanceMode::Long).expect("set_distance_mode");
    full.set_timing_budget(100000).expect("set_timing_budget");

    full.set_signal_rate_limit(0.5).expect("set_signal_rate_limit");
    check_true!(full.signal_rate_limit().expect("signal_rate_limit") == 0.5, "signal_rate_roundtrip", passed, failed);
    full.set_signal_rate_limit(1.0).expect("set_signal_rate_limit");
    full.set_sigma_threshold(60).expect("set_sigma_threshold");
    check_true!(full.sigma_threshold().expect("sigma_threshold") == 60, "sigma_roundtrip", passed, failed);
    full.set_sigma_threshold(90).expect("set_sigma_threshold");

    full.set_roi(8, 8).expect("set_roi");
    check_true!(full.roi().expect("roi") == (8, 8), "roi_roundtrip", passed, failed);
    let _ = full.optical_center().expect("optical_center");
    full.set_roi(16, 16).expect("set_roi");
    check_true!(full.roi().expect("roi") == (16, 16) && full.roi_center().expect("roi_center") == 199,
        "roi_restored", passed, failed);

    let original = full.offset().expect("offset");
    full.set_offset(-10.25).expect("set_offset");
    check_true!(full.offset().expect("offset") == -10.25, "offset_roundtrip", passed, failed);
    full.set_offset(original).expect("set_offset");
    full.set_crosstalk_compensation(0.01).expect("set_crosstalk_compensation");
    let xt = full.crosstalk_compensation().expect("crosstalk_compensation");
    check_true!(xt > 0.0099 && xt < 0.0101, "crosstalk_roundtrip", passed, failed);
    full.set_crosstalk_compensation(0.0).expect("set_crosstalk_compensation");

    full.set_interrupt_thresholds(100, 800).expect("set_interrupt_thresholds");
    check_true!(full.interrupt_thresholds().expect("interrupt_thresholds") == (100, 800), "thresholds_roundtrip", passed, failed);

    full.start_continuous(150).expect("start_continuous");
    let period = full.inter_measurement().expect("inter_measurement");
    check_true!((148..=150).contains(&period), "inter_measurement", passed, failed);
    let mut cont_ok = true;
    for _ in 0..3 {
        cont_ok = full.read_continuous().is_ok() && cont_ok;
    }
    check_true!(cont_ok, "continuous_readings", passed, failed);
    sleep(Duration::from_millis(200));
    check_true!(full.poll_interrupt().expect("poll_interrupt") == VL53L1X_SOURCE_NEW_SAMPLE_READY,
        "poll_interrupt_new_sample", passed, failed);
    full.stop_continuous().expect("stop_continuous");
    sleep(Duration::from_millis(200));
    full.poll_interrupt().expect("poll_interrupt");

    check_true!(full.recalibrate().is_ok(), "recalibrate", passed, failed);
    check_true!(full.distance().is_ok(), "recalibrate_then_distance", passed, failed);
    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
