use linux_embedded_hal::I2cdev;
use periph::chips::adc_dac::AD5243Full;

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
        .unwrap_or(0x2F);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut pot = AD5243Full::new(dev, addr, 10000.0, 160.0);

    pot.set_wiper(0, 100).expect("set");
    check_true!(pot.get_wiper(0).expect("get") == 100, "wiper0_roundtrip", passed, failed);
    pot.set_wiper(1, 200).expect("set");
    check_true!(pot.get_wiper(1).expect("get") == 200, "wiper1_roundtrip", passed, failed);
    check_true!(pot.get_wiper(0).expect("get") == 100, "wiper0_independent", passed, failed);
    pot.set_wiper(0, 300).expect("set");
    check_true!(pot.get_wiper(0).expect("get") == 255, "wiper_clamps_255", passed, failed);
    pot.set_resistance(0, 5000.0).expect("set R");
    check_true!((pot.get_resistance(0).expect("R") - 5000.0).abs() < 50.0, "set_resistance", passed, failed);

    pot.set_both(10, 250).expect("both");
    check_true!(pot.get_wiper(0).expect("get") == 10 && pot.get_wiper(1).expect("get") == 250, "set_both", passed, failed);
    pot.set_ratio(0, 0.5).expect("ratio");
    check_true!(pot.get_wiper(0).expect("get") == 128 && (pot.get_ratio(0).expect("ratio") - 0.5).abs() < 0.01, "ratio_half", passed, failed);
    pot.set_resistance_wa(1, 3000.0).expect("wa");
    check_true!((pot.get_resistance_wa(1).expect("wa") - 3000.0).abs() < 50.0, "set_resistance_wa", passed, failed);
    pot.set_wiper(0, 100).expect("set");
    check_true!(pot.increment(0, 5).expect("inc") == 105 && pot.get_wiper(0).expect("get") == 105, "increment", passed, failed);
    check_true!(pot.increment(0, -500).expect("inc") == 0, "increment_clamps", passed, failed);

    pot.set_wiper(1, 77).expect("set");
    pot.shutdown(1).expect("shutdown");
    check_true!(pot.is_shutdown(1).expect("sd"), "is_shutdown", passed, failed);
    check_true!(pot.get_wiper(1).expect("get") == 77, "shutdown_keeps_rdac", passed, failed);
    pot.resume(1).expect("resume");
    check_true!(!pot.is_shutdown(1).expect("sd") && pot.get_wiper(1).expect("get") == 77, "resume", passed, failed);

    pot.set_both(1, 2).expect("both");
    pot.reset().expect("reset");
    check_true!(pot.get_wiper(0).expect("get") == 128 && pot.get_wiper(1).expect("get") == 128, "reset_midscale", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    if failed > 0 { std::process::exit(1); }
}
