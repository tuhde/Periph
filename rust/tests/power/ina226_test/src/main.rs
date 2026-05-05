use linux_embedded_hal::I2cdev;
use periph::chips::power::{Ina226Full, BOL};

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
        .unwrap_or(0x40);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Ina226Full::new(dev, addr, 0.1, 2.0).expect("init INA226");

    let mut passed = 0i32;
    let mut failed = 0i32;

    check_true!(
        chip.manufacturer_id().unwrap() == 0x5449,
        "manufacturer_id",
        passed,
        failed
    );
    check_true!(
        chip.die_id().unwrap() == 0x2260,
        "die_id",
        passed,
        failed
    );

    chip.configure(0, 4, 4, 7).unwrap();

    let v = chip.voltage().unwrap();
    check_true!(v >= 0.0 && v <= 40.0, "voltage_range", passed, failed);

    let sv = chip.shunt_voltage().unwrap();
    check_true!(sv >= -0.082 && sv <= 0.082, "shunt_voltage_range", passed, failed);

    let i = chip.current().unwrap();
    check_true!(i >= -2.0 && i <= 2.0, "current_range", passed, failed);

    let p = chip.power().unwrap();
    check_true!(p >= 0.0 && p <= 80.0, "power_range", passed, failed);

    check_true!(
        chip.conversion_ready().is_ok(),
        "conversion_ready_ok",
        passed,
        failed
    );
    check_true!(
        chip.overflow().is_ok(),
        "overflow_ok",
        passed,
        failed
    );

    chip.set_alert(BOL, 5.0, false, false).unwrap();
    let flags = chip.alert_flags().unwrap();
    check_true!(flags & BOL != 0, "alert_flags_bol_set", passed, failed);

    chip.shutdown().unwrap();
    chip.wake().unwrap();
    check_true!(true, "shutdown_wake", passed, failed);

    chip.reset().unwrap();
    check_true!(true, "reset", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
