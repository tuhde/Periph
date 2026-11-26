//! CCS811 hardware-in-loop test for Linux.
use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::gas::ccs811;
use periph::chips::gas::Ccs811Full;

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x5A);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }
    let mut chip = Ccs811Full::new(dev, addr);

    chip.init(&mut delay).expect("init");
    let (hw_id, hw_version) = chip.hardware_version().expect("hw");
    check_true!(hw_id == 0x81, "hardware_id", passed, failed);
    check_true!(hw_version & 0xF0 == 0x10, "hardware_version", passed, failed);
    let (_, app) = chip.firmware_version().expect("fw");
    check_true!(app.major >= 1, "firmware_version", passed, failed);
    let st = chip.status().expect("status");
    check_true!(st & ccs811::STATUS_FW_MODE != 0, "status_app_mode", passed, failed);
    check_true!(st & ccs811::STATUS_ERROR == 0, "status_no_error", passed, failed);
    let m = chip.read(&mut delay).expect("read");
    check_true!(m.eco2_ppm >= 400 && m.eco2_ppm <= 8192, "read_eco2_range", passed, failed);
    check_true!(m.tvoc_ppb <= 1187, "read_tvoc_range", passed, failed);
    let e = chip.read_eco2(&mut delay).expect("eco2");
    check_true!(e >= 400 && e <= 8192, "read_eco2_single", passed, failed);
    check_true!(chip.read_tvoc(&mut delay).expect("tvoc") <= 1187, "read_tvoc_single", passed, failed);
    let (current, voltage) = chip.read_raw().expect("raw");
    check_true!(current <= 63, "read_raw_current", passed, failed);
    check_true!(voltage >= 0.0 && voltage <= 1.65, "read_raw_voltage", passed, failed);
    check_true!(!chip.data_ready().expect("ready"), "data_ready_cleared", passed, failed);
    check_true!(chip.set_environment(50.0, 25.0).is_ok(), "set_environment", passed, failed);
    check_true!(chip.set_thresholds(1000, 2000, 50).is_ok(), "set_thresholds", passed, failed);
    check_true!(chip.read_ntc().is_ok(), "read_ntc", passed, failed);
    let baseline = chip.get_baseline().expect("baseline");
    chip.set_baseline(baseline).expect("baseline");
    check_true!(chip.error().expect("error") == 0, "baseline_roundtrip", passed, failed);
    chip.enable_interrupt(ccs811::SOURCE_DATA_READY).expect("enable");
    sleep_ms!(1200);
    check_true!(chip.poll_interrupt().expect("poll") & ccs811::STATUS_DATA_READY != 0, "poll_interrupt_data_ready", passed, failed);
    chip.read(&mut delay).expect("read");
    chip.disable_interrupt(ccs811::SOURCE_DATA_READY).expect("disable");
    check_true!(chip.set_mode(4).is_ok(), "set_mode_4", passed, failed);
    sleep_ms!(600);
    check_true!(chip.read_raw().expect("raw").0 <= 63, "mode_4_raw", passed, failed);
    chip.set_mode(0).expect("mode");
    chip.set_mode(1).expect("mode");
    check_true!(chip.soft_reset(&mut delay).is_ok(), "soft_reset", passed, failed);
    check_true!(chip.status().expect("status") & ccs811::STATUS_FW_MODE != 0, "status_after_reset", passed, failed);
    let r = chip.read(&mut delay).expect("read");
    check_true!(r.eco2_ppm >= 400 && r.eco2_ppm <= 8192, "read_after_reset", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    if failed > 0 { std::process::exit(1); }
}
