use linux_embedded_hal::I2cdev;
use periph::chips::power::{Axp2101Full, Axp2101Minimal};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x34);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Axp2101Minimal::new(dev, addr).expect("init AXP2101");
    std::thread::sleep(std::time::Duration::from_millis(20));

    let mut passed = 0i32;
    let mut failed = 0i32;
    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { println!("PASS {}", $label); passed += 1; }
            else      { println!("FAIL {}", $label); failed += 1; }
        };
    }

    let v = chip.battery_voltage().expect("battery_voltage");
    let pct = chip.battery_percent().expect("battery_percent");
    let state = chip.charge_state().expect("charge_state");
    check_true!(v >= 0.0 && v <= 5.0, "battery_voltage_plausible");
    check_true!(pct <= 100, "battery_percent_range");
    check_true!(state <= 5, "charge_state_range");
    check_true!(chip.vbus_present().is_ok(), "vbus_present_no_error");

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut full = Axp2101Full::new(dev, addr).expect("init AXP2101 Full");
    check_true!(full.vbus_voltage().map(|x| x >= 0.0 && x <= 15.0).unwrap_or(false), "vbus_voltage_range");
    check_true!(full.system_voltage().map(|x| x >= 0.0 && x <= 6.0).unwrap_or(false), "system_voltage_range");
    check_true!(full.ts_voltage().map(|x| x >= 0.0 && x <= 4.0).unwrap_or(false), "ts_voltage_range");
    check_true!(full.die_temperature().map(|x| x >= -40.0 && x <= 150.0).unwrap_or(false), "die_temperature_range");
    check_true!(full.read_all().is_ok(), "read_all");
    check_true!(full.status().map(|s| ["standby", "charge", "discharge", "reserved"].contains(&s.battery_direction)).unwrap_or(false), "status_direction");
    check_true!(full.get_rail("dcdc1").map(|(_, volts)| volts >= 1.4 && volts <= 3.5).unwrap_or(false), "get_rail_dcdc1");
    check_true!(full.set_input_voltage_limit(4.36).is_ok(), "set_input_voltage_limit");
    check_true!(full.write_data_buffer(0, 0x5A).is_ok(), "write_data_buffer");
    check_true!(full.read_data_buffer(0).map(|b| b == 0x5A).unwrap_or(false), "data_buffer_roundtrip");
    check_true!(full.enable_interrupt(periph::chips::power::axp2101::SOURCE_VBUS_INSERT).is_ok(), "enable_interrupt");
    check_true!(full.poll_interrupt().is_ok(), "poll_interrupt");

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
