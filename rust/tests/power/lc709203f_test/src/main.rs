use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::power::Lc709203fFull;

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
        .unwrap_or(0x0B);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut chip = Lc709203fFull::new(dev, addr, 1000, 1, &mut delay).expect("init LC709203F");

    let mut passed = 0i32;
    let mut failed = 0i32;

    check_true!(chip.voltage().map(|v| v >= 2.5 && v <= 4.6).unwrap_or(false), "voltage_range", passed, failed);
    check_true!(chip.percent().map(|v| v >= 0.0 && v <= 100.0).unwrap_or(false), "percent_range", passed, failed);
    check_true!(chip.temperature().map(|v| v > 0.0 && v < 60.0).unwrap_or(false), "temperature_range", passed, failed);
    check_true!(chip.rsoc().map(|v| v <= 100).unwrap_or(false), "rsoc_range", passed, failed);
    check_true!(chip.profile_id().map(|v| v != 0).unwrap_or(false), "profile_id", passed, failed);
    check_true!(chip.get_profile().map(|v| v == 1).unwrap_or(false), "get_profile", passed, failed);
    check_true!(chip.set_temperature(30.0).is_ok() && chip.temperature().map(|t| (t - 30.0).abs() < 0.2).unwrap_or(false), "set_temperature", passed, failed);
    check_true!(chip.set_apa(0x19).is_ok() && chip.get_apa().map(|v| v == 0x19).unwrap_or(false), "apa", passed, failed);
    check_true!(chip.set_apt(0x1E).is_ok() && chip.get_apt().map(|v| v == 0x1E).unwrap_or(false), "apt", passed, failed);
    check_true!(chip.set_alarm_rsoc(20).is_ok() && chip.get_alarm_rsoc().map(|v| v == 20).unwrap_or(false), "alarm_rsoc", passed, failed);
    check_true!(chip.set_alarm_voltage(3.5).is_ok() && chip.get_alarm_voltage().map(|v| (v - 3.5).abs() < 0.001).unwrap_or(false), "alarm_voltage", passed, failed);
    check_true!(chip.poll_interrupt().map(|v| v <= 3).unwrap_or(false), "poll_interrupt", passed, failed);
    check_true!(chip.init_rsoc(&mut delay).is_ok() && chip.rsoc().map(|v| v <= 100).unwrap_or(false), "init_rsoc", passed, failed);
    check_true!(chip.sleep().is_ok() && chip.is_sleeping(&mut delay).unwrap_or(false), "sleep", passed, failed);
    check_true!({ chip.wake(&mut delay); chip.is_sleeping(&mut delay).map(|s| !s).unwrap_or(false) }, "wake", passed, failed);
    check_true!(chip.voltage().map(|v| v > 0.0).unwrap_or(false), "voltage_after_wake", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
