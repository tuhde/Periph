#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_println::println;
use periph::chips::tof::{Vl6180xError, Vl6180xFull, VL6180X_MODE_NEW_SAMPLE_READY, VL6180X_MODE_OUT_OF_WINDOW, VL6180X_SOURCE_RANGE};

esp_app_desc!();

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

const ADDR: u8 = 0x29;

#[main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());
    let mut delay = Delay::new();

    let mut passed = 0i32;
    let mut failed = 0i32;

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .expect("i2c config")
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut sensor = Vl6180xFull::new(i2c, ADDR);
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
    delay.delay_millis(200);

    sensor.set_range_thresholds(30, 80).expect("set_range_thresholds");
    sensor.enable_interrupt(VL6180X_SOURCE_RANGE, VL6180X_MODE_OUT_OF_WINDOW).expect("enable_interrupt");
    sensor.start_continuous_range(50).expect("start_continuous_range");
    delay.delay_millis(300);
    check_true!(sensor.poll_interrupt().expect("poll_interrupt") <= 7, "poll_interrupt_mask", passed, failed);
    sensor.stop_continuous_range().expect("stop_continuous_range");
    delay.delay_millis(200);
    sensor.disable_interrupt(VL6180X_SOURCE_RANGE).expect("disable_interrupt");
    sensor.enable_interrupt(VL6180X_SOURCE_RANGE, VL6180X_MODE_NEW_SAMPLE_READY).expect("enable_interrupt");
    sensor.poll_interrupt().expect("poll_interrupt");

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {
        delay.delay_millis(1000);
    }
}
