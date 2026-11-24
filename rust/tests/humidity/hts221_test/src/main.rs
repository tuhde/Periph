use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::humidity::HTS221Full;

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
        .unwrap_or(0x5F);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }
    let mut chip = HTS221Full::new(dev, addr);

    chip.init().expect("init");
    check_true!(chip.who_am_i().expect("who_am_i") == 0xBC, "who_am_i", passed, failed);
    let cal = chip.read_calibration();
    check_true!(cal.h1_rh > cal.h0_rh && cal.t1_degc > cal.t0_degc, "calibration_plausible", passed, failed);
    let (t, rh) = chip.read(&mut delay).expect("read");
    check_true!(t > -40.0 && t < 85.0, "read_temperature_range", passed, failed);
    check_true!(rh >= 0.0 && rh <= 100.0, "read_humidity_range", passed, failed);
    let h = chip.read_humidity(&mut delay).expect("rh");
    check_true!(h >= 0.0 && h <= 100.0, "read_humidity_single", passed, failed);
    check_true!(chip.read_temperature(&mut delay).expect("t") > -40.0, "read_temperature_single", passed, failed);
    let dew = chip.dew_point(&mut delay).expect("dew");
    check_true!(dew <= t + 1.0, "dew_point_below_temperature", passed, failed);
    chip.set_averaging(32, 64).expect("averaging");
    check_true!(chip.get_averaging().expect("get averaging") == (32, 64), "averaging_roundtrip", passed, failed);
    chip.set_averaging(16, 32).expect("averaging");
    chip.set_output_data_rate(1.0).expect("odr");
    check_true!(chip.get_output_data_rate().expect("get odr") == 1.0, "odr_roundtrip", passed, failed);
    sleep_ms!(1500);
    let (_, rhc) = chip.read_continuous().expect("continuous");
    check_true!(rhc >= 0.0 && rhc <= 100.0, "continuous_read", passed, failed);
    chip.set_output_data_rate(0.0).expect("odr");
    chip.set_heater(true).expect("heater on");
    check_true!(chip.is_heater_on().expect("is_heater_on"), "heater_on", passed, failed);
    chip.set_heater(false).expect("heater off");
    check_true!(!chip.is_heater_on().expect("is_heater_on"), "heater_off", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    if failed > 0 { std::process::exit(1); }
}
