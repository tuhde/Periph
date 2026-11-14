use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::tof::{Vl6180xError, Vl6180xFull, VL6180X_MODE_NEW_SAMPLE_READY, VL6180X_MODE_OUT_OF_WINDOW, VL6180X_SOURCE_RANGE};
use std::thread::sleep;
use std::time::Duration;

fn sleep_ms(ms: u64) {
    sleep(Duration::from_millis(ms));
}

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
    let mut delay = Delay;

    let i2c = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut sensor = Vl6180xFull::new(i2c, addr);
    sensor.init(&mut delay).expect("init VL6180X");
    check_true!(true, "init", passed, failed);

    check_true!(sensor.model_id().expect("model_id") == 0xB4, "model_id", passed, failed);
    check_true!(sensor.revision().expect("revision").0 >= 1, "revision", passed, failed);

    check_true!(!matches!(sensor.read_range(&mut delay), Err(Vl6180xError::Timeout) | Err(Vl6180xError::Bus(_))), "read_range_completes", passed, failed);
    let status = sensor.read_range_status(&mut delay);
    check_true!(matches!(status, Ok((_, e)) if e <= 15), "range_status", passed, failed);

    check_true!(sensor.read_als_raw(&mut delay).is_ok(), "als_raw", passed, failed);
    check_true!(sensor.read_lux(&mut delay).map(|l| l >= 0.0).unwrap_or(false), "lux_positive", passed, failed);
    for code in [0u8, 6, 7] {
        sensor.set_als_gain(code).expect("set_als_gain");
        check_true!(sensor.read_lux(&mut delay).is_ok(), "lux_at_gain", passed, failed);
    }
    sensor.set_als_gain(6).expect("set_als_gain");
    sensor.set_als_integration_time(50).expect("set_als_integration_time");
    check_true!(sensor.read_lux(&mut delay).is_ok(), "lux_50ms", passed, failed);
    sensor.set_als_integration_time(100).expect("set_als_integration_time");
    sensor.set_als_scaler(1).expect("set_als_scaler");

    sensor.set_range_offset(3).expect("set_range_offset");
    check_true!(sensor.get_range_offset().expect("get_range_offset") == 3, "offset_roundtrip", passed, failed);
    sensor.set_range_offset(0).expect("set_range_offset");
    sensor.set_crosstalk_rate(0.0).expect("set_crosstalk_rate");
    sensor.set_max_convergence_time(49).expect("set_max_convergence_time");
    sensor.set_averaging_period(48).expect("set_averaging_period");
    check_true!(sensor.recalibrate_vhv(&mut delay).is_ok(), "recalibrate_vhv", passed, failed);
    check_true!(sensor.read_range_status(&mut delay).is_ok(), "range_after_recalibration", passed, failed);

    sensor.start_continuous_range(50).expect("start_continuous_range");
    let mut cont_ok = true;
    for _ in 0..5 {
        cont_ok = !matches!(sensor.read_range_continuous(&mut delay), Err(Vl6180xError::Timeout) | Err(Vl6180xError::Bus(_))) && cont_ok;
    }
    check_true!(cont_ok, "continuous_range", passed, failed);
    sensor.stop_continuous_range().expect("stop_continuous_range");
    sleep_ms(200);

    sensor.set_range_thresholds(30, 80).expect("set_range_thresholds");
    sensor.enable_interrupt(VL6180X_SOURCE_RANGE, VL6180X_MODE_OUT_OF_WINDOW).expect("enable_interrupt");
    sensor.start_continuous_range(50).expect("start_continuous_range");
    sleep_ms(300);
    check_true!(sensor.poll_interrupt().expect("poll_interrupt") <= 7, "poll_interrupt_mask", passed, failed);
    sensor.stop_continuous_range().expect("stop_continuous_range");
    sleep_ms(200);
    sensor.disable_interrupt(VL6180X_SOURCE_RANGE).expect("disable_interrupt");
    sensor.enable_interrupt(VL6180X_SOURCE_RANGE, VL6180X_MODE_NEW_SAMPLE_READY).expect("enable_interrupt");
    sensor.poll_interrupt().expect("poll_interrupt");

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
