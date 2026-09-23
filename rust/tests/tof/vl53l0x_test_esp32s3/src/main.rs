#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_println::println;
use periph::chips::tof::{
    Vl53l0xFull, Vl53l0xMinimal, Vl53l0xProfile, Vl53l0xVcselPeriodType, VL53L0X_SOURCE_NEW_SAMPLE_READY,
};

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
    let delay = Delay::new();

    let mut passed = 0i32;
    let mut failed = 0i32;

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .expect("i2c config")
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut minimal = Vl53l0xMinimal::new(i2c, ADDR, Delay::new()).expect("init VL53L0X");
    let d = minimal.distance().expect("distance");
    check_true!(d <= 8191, "distance_in_range", passed, failed);
    let _ = minimal.range_valid();
    let (i2c, driver_delay) = minimal.release();

    let mut full = Vl53l0xFull::new(i2c, ADDR, driver_delay).expect("init VL53L0X Full");
    check_true!(full.model_id().expect("model_id") == 0xEE, "model_id", passed, failed);
    check_true!(full.revision_id().expect("revision_id") > 0, "revision_id", passed, failed);
    let budget = full.timing_budget().expect("timing_budget");
    check_true!((20000..=40000).contains(&budget), "default_budget", passed, failed);

    full.distance().expect("distance");
    let m = full.read_measurement().expect("read_measurement");
    check_true!(m.range_status <= 15 && m.signal_rate_mcps >= 0.0, "measurement_record", passed, failed);

    full.set_timing_budget(50000).expect("set_timing_budget");
    check_true!(full.timing_budget().expect("timing_budget").abs_diff(50000) < 300, "budget_roundtrip", passed, failed);
    full.set_signal_rate_limit(0.1).expect("set_signal_rate_limit");
    let limit = full.signal_rate_limit().expect("signal_rate_limit");
    check_true!(limit > 0.09 && limit < 0.11, "signal_rate_roundtrip", passed, failed);
    full.set_vcsel_pulse_period(Vl53l0xVcselPeriodType::PreRange, 18).expect("vcsel pre");
    full.set_vcsel_pulse_period(Vl53l0xVcselPeriodType::FinalRange, 14).expect("vcsel final");
    check_true!(full.vcsel_pulse_period(Vl53l0xVcselPeriodType::PreRange).expect("vcsel") == 18
        && full.vcsel_pulse_period(Vl53l0xVcselPeriodType::FinalRange).expect("vcsel") == 14, "vcsel_roundtrip", passed, failed);
    full.set_profile(Vl53l0xProfile::Default).expect("set_profile");
    check_true!(full.vcsel_pulse_period(Vl53l0xVcselPeriodType::PreRange).expect("vcsel") == 14
        && full.timing_budget().expect("timing_budget").abs_diff(33000) < 300, "profile_default", passed, failed);

    let original = full.offset().expect("offset");
    full.set_offset(-10.25).expect("set_offset");
    check_true!(full.offset().expect("offset") == -10.25, "offset_roundtrip", passed, failed);
    full.set_offset(original).expect("set_offset");

    full.set_interrupt_thresholds(100, 800).expect("set_interrupt_thresholds");
    check_true!(full.interrupt_thresholds().expect("interrupt_thresholds") == (100, 800), "thresholds_roundtrip", passed, failed);

    // Back-to-back continuous ranging, then timed mode.
    full.start_continuous(0).expect("start_continuous");
    let ok = (0..3).all(|_| full.read_continuous().map(|r| r <= 8191).unwrap_or(false));
    check_true!(ok, "continuous_readings", passed, failed);
    full.stop_continuous().expect("stop_continuous");
    delay.delay_millis(50);
    full.poll_interrupt().expect("poll_interrupt");
    full.start_continuous(100).expect("start_continuous");
    let ok = (0..2).all(|_| full.read_continuous().map(|r| r <= 8191).unwrap_or(false));
    check_true!(ok, "timed_readings", passed, failed);
    full.stop_continuous().expect("stop_continuous");
    delay.delay_millis(150);
    full.poll_interrupt().expect("poll_interrupt");

    full.start_continuous(0).expect("start_continuous");
    delay.delay_millis(100);
    check_true!(full.poll_interrupt().expect("poll_interrupt") == VL53L0X_SOURCE_NEW_SAMPLE_READY,
        "poll_interrupt_new_sample", passed, failed);
    full.stop_continuous().expect("stop_continuous");
    delay.delay_millis(50);
    full.poll_interrupt().expect("poll_interrupt");

    check_true!(full.recalibrate().is_ok(), "recalibrate", passed, failed);
    check_true!(full.distance().map(|d| d <= 8191).unwrap_or(false), "recalibrate_then_distance", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {
        delay.delay_millis(1000);
    }
}
