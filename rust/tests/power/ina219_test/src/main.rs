use linux_embedded_hal::I2cdev;
use periph::chips::power::Ina219Full;

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
    let mut chip = Ina219Full::new(dev, addr, 0.1, 2.0).expect("init INA219");

    let mut passed = 0i32;
    let mut failed = 0i32;

    let v = chip.voltage().unwrap();
    check_true!(v >= 0.0 && v <= 40.0, "voltage_range", passed, failed);

    let sv = chip.shunt_voltage().unwrap();
    check_true!(sv >= -0.32 && sv <= 0.32, "shunt_voltage_range", passed, failed);

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

    chip.configure(1, 3, 3, 3, 7).unwrap();
    check_true!(chip.voltage().unwrap() >= 0.0, "voltage_after_configure", passed, failed);

    chip.shutdown().unwrap();
    chip.wake().unwrap();
    check_true!(true, "shutdown_wake", passed, failed);

    chip.reset().unwrap();
    check_true!(true, "reset", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
