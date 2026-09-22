use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::power::Ade7953Minimal;

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
        .unwrap_or(0x38);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut ade = Ade7953Minimal::new(dev, addr, 251.0, 30.0, &mut delay).expect("init ADE7953");

    let mut passed = 0i32;
    let mut failed = 0i32;
    check_true!(ade.voltage().unwrap_or(-1.0) >= 0.0, "voltage non-negative", passed, failed);
    check_true!(ade.current().unwrap_or(-1.0) >= 0.0, "current non-negative", passed, failed);
    check_true!(ade.active_power().unwrap_or(-1.0e6) > -1.0e6, "activePower finite", passed, failed);
    check_true!(ade.active_energy().unwrap_or(-1000.0) > -1000.0, "activeEnergy finite", passed, failed);
    check_true!(ade.line_period().unwrap_or(0.0) > 0.0, "linePeriod positive", passed, failed);

    ade.reset(&mut delay).expect("reset");
    check_true!(ade.voltage().unwrap_or(-1.0) >= 0.0, "voltage after reset", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}