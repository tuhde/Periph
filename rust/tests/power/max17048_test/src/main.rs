use linux_embedded_hal::I2cdev;
use periph::chips::power::Max17048Full;

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
        .unwrap_or(0x36);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Max17048Full::new(dev, addr, 1).expect("init MAX17048");

    let mut passed = 0i32;
    let mut failed = 0i32;

    check_true!(chip.voltage().map(|v| v >= 2.5 && v <= 4.6).unwrap_or(false), "voltage_range", passed, failed);
    check_true!(chip.percent().map(|v| v >= 0.0 && v <= 100.0).unwrap_or(false), "percent_range", passed, failed);
    check_true!(chip.version().map(|v| v & 0xFFF0 == 0x0010).unwrap_or(false), "version", passed, failed);
    check_true!(chip.raw_voltage().map(|r| ((r >> 4) as f32 * 1.25e-3) > 2.5).unwrap_or(false), "raw_voltage", passed, failed);
    check_true!(chip.charge_rate().map(|v| v.abs() < 1000.0).unwrap_or(false), "charge_rate", passed, failed);
    check_true!(chip.set_rcomp(0x97).is_ok() && chip.get_rcomp().map(|v| v == 0x97).unwrap_or(false), "rcomp", passed, failed);
    check_true!(chip.compensate_temperature(20.0).is_ok() && chip.get_rcomp().map(|v| v == 0x97).unwrap_or(false), "compensate_20c", passed, failed);
    check_true!(chip.set_alert_soc(10).is_ok() && chip.get_alert_soc().map(|v| v == 10).unwrap_or(false), "alert_soc", passed, failed);
    let _ = chip.set_alert_soc(4);
    check_true!(chip.set_voltage_alert(3.0, 4.2).is_ok() && chip.get_voltage_alert().map(|(lo, hi)| (lo - 3.0).abs() < 0.001 && (hi - 4.2).abs() < 0.001).unwrap_or(false), "voltage_alert", passed, failed);
    let _ = chip.set_voltage_alert(0.0, 5.1);
    check_true!(chip.set_reset_voltage(2.8).is_ok() && chip.get_reset_voltage().map(|v| (v - 2.8).abs() < 0.001).unwrap_or(false), "reset_voltage", passed, failed);
    let _ = chip.set_reset_voltage(3.0);
    check_true!(chip.set_hibernate_thresholds(26.624, 0.06).is_ok() && chip.get_hibernate_thresholds().map(|(h, a)| (h - 26.624).abs() < 0.001 && (a - 0.06).abs() < 0.001).unwrap_or(false), "hibernate_thresholds", passed, failed);
    check_true!(chip.clear_status(0x3F00).is_ok() && chip.read_status().map(|v| v & 0x3F00 == 0).unwrap_or(false), "status_cleared", passed, failed);
    check_true!(chip.poll_interrupt().map(|v| v == 0).unwrap_or(false), "poll_interrupt", passed, failed);
    check_true!(chip.read_config().and_then(|c| chip.write_config(c)).is_ok() && chip.read_config().map(|v| v >> 8 == 0x97).unwrap_or(false), "config", passed, failed);
    check_true!(chip.enable_sleep(true).is_ok() && chip.sleep().is_ok(), "sleep", passed, failed);
    std::thread::sleep(std::time::Duration::from_millis(10));
    check_true!(chip.wake().is_ok() && chip.enable_sleep(false).is_ok(), "wake", passed, failed);
    check_true!(chip.voltage().map(|v| v > 0.0).unwrap_or(false), "voltage_after_wake", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
