use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::power::{Ltc4151Full};
use periph::chips::power::CH_VIN;

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
        .unwrap_or(0x6A);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Ltc4151Full::new(dev, addr, 0.02).expect("init LTC4151");

    let mut passed = 0i32;
    let mut failed = 0i32;

    check_true!(chip.voltage().map(|v| v >= 7.0 && v <= 80.0).unwrap_or(false), "voltage_range", passed, failed);
    check_true!(chip.current().map(|v| v >= 0.0 && v <= 4.096).unwrap_or(false), "current_range", passed, failed);
    check_true!(chip.power().map(|v| v >= 0.0 && v <= 330.0).unwrap_or(false), "power_range", passed, failed);
    check_true!(chip.sense_voltage().map(|v| v >= 0.0 && v <= 0.08192).unwrap_or(false), "sense_voltage_range", passed, failed);
    check_true!(chip.adin_voltage().map(|v| v >= 0.0 && v <= 2.048).unwrap_or(false), "adin_range", passed, failed);
    check_true!(chip.read_all().map(|r| r.vin >= 7.0 && r.vin <= 80.0).unwrap_or(false), "read_all_vin", passed, failed);
    check_true!(chip.read_raw(CH_VIN).map(|c| c > 0 && c < 4096).unwrap_or(false), "read_raw_vin", passed, failed);
    check_true!(chip.read_control().map(|c| c == 0x0C).unwrap_or(false), "control_default", passed, failed);
    check_true!(chip.is_snapshot_mode().map(|m| !m).unwrap_or(false), "not_snapshot_mode", passed, failed);
    check_true!(chip.snapshot(CH_VIN, &mut Delay).map(|v| v >= 7.0 && v <= 80.0).unwrap_or(false), "snapshot_vin", passed, failed);
    check_true!(chip.is_snapshot_mode().map(|m| !m).unwrap_or(false), "back_in_continuous", passed, failed);
    chip.set_stuck_bus_timer(false).ok();
    check_true!(chip.read_control().map(|c| c == 0x08).unwrap_or(false), "stuck_bus_timer_off", passed, failed);
    chip.set_stuck_bus_timer(true).ok();
    check_true!(chip.read_control().map(|c| c == 0x0C).unwrap_or(false), "stuck_bus_timer_on", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
