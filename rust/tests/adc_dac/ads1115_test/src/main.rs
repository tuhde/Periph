use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::adc_dac::ads1115::{ADS1115_ACTIVE_LOW, ADS1115_COMP_WINDOW, ADS1115_DIFF_2_3, ADS1115_DR_860, ADS1115_PGA_4_096, ADS1115_QUEUE_4};
use periph::chips::adc_dac::{Ads1115Full, Ads1115Minimal};
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
        .unwrap_or(0x48);

    let mut passed = 0i32;
    let mut failed = 0i32;
    let mut delay = Delay;

    let i2c = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut minimal = Ads1115Minimal::new(i2c, addr);
    let raw = minimal.read_raw(0, &mut delay).expect("read_raw");
    let raw2 = minimal.read_raw(0, &mut delay).expect("read_raw");
    check_true!((raw as i32 - raw2 as i32).abs() < 4000, "read_raw_stable", passed, failed);
    let v = minimal.read_voltage(0, &mut delay).expect("read_voltage");
    check_true!((v - raw as f32 * 0.0000625).abs() < 0.25, "read_voltage_matches_raw", passed, failed);
    let i2c = minimal.release();

    let mut full = Ads1115Full::new(i2c, addr);
    full.set_data_rate(ADS1115_DR_860).expect("set_data_rate");
    check_true!(full.get_data_rate() == 7 && full.data_rate_sps() == 860, "data_rate_roundtrip", passed, failed);
    full.set_gain(ADS1115_PGA_4_096).expect("set_gain");
    check_true!(full.get_gain() == 1 && full.full_scale() == 4.096, "gain_roundtrip", passed, failed);
    full.set_gain(2).expect("set_gain");
    full.start_continuous(4).expect("start_continuous");
    sleep(Duration::from_millis(50));
    let cont = full.read_continuous_raw().expect("read_continuous_raw");
    check_true!((cont as i32 - raw as i32).abs() < 8000, "continuous_matches_single_shot", passed, failed);
    check_true!(full.read_config().expect("read_config") & 0x0100 == 0, "continuous_config_mode", passed, failed);
    full.stop_continuous().expect("stop_continuous");
    check_true!(full.read_config().expect("read_config") & 0x0100 != 0, "single_shot_config_mode", passed, failed);
    for ch in 0..4u8 {
        check_true!(full.read_raw(ch, &mut delay).expect("read_raw") > -2000, "single_ended_channel", passed, failed);
    }
    full.read_differential_raw(ADS1115_DIFF_2_3, &mut delay).expect("read_differential_raw");
    check_true!(!full.is_converting().expect("is_converting"), "differential_done", passed, failed);
    full.set_low_threshold_raw(-1000).expect("set_low_threshold_raw");
    full.set_high_threshold_raw(1000).expect("set_high_threshold_raw");
    check_true!(full.get_thresholds_raw().expect("get_thresholds_raw") == (-1000, 1000), "threshold_roundtrip", passed, failed);
    full.configure_comparator(ADS1115_COMP_WINDOW, ADS1115_ACTIVE_LOW, false, ADS1115_QUEUE_4).expect("configure_comparator");
    check_true!(full.read_config().expect("read_config") & 0x0017 == 0x0012, "comparator_bits", passed, failed);
    full.disable_comparator().expect("disable_comparator");
    check_true!(full.read_config().expect("read_config") & 0x0003 == 0x0003, "comparator_disabled", passed, failed);
    full.reset().expect("reset");
    check_true!(full.read_config().expect("read_config") & 0x7FFF == 0x0583, "reset_config", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
