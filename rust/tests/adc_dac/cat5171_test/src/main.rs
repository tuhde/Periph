use linux_embedded_hal::I2cdev;
use periph::chips::adc_dac::CAT5171Full;

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
        .unwrap_or(0x2C);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut pot = CAT5171Full::new(dev, addr, 100000.0, 50.0);

    pot.set_wiper(100).expect("set");
    check_true!(pot.get_wiper().expect("get") == 100, "wiper_roundtrip", passed, failed);
    pot.set_wiper(300).expect("set");
    check_true!(pot.get_wiper().expect("get") == 255, "wiper_clamps_255", passed, failed);
    pot.set_resistance(50000.0).expect("set R");
    check_true!((pot.get_resistance().expect("R") - 50000.0).abs() < 500.0, "set_resistance", passed, failed);

    pot.set_ratio(0.5).expect("ratio");
    check_true!(pot.get_wiper().expect("get") == 128 && (pot.get_ratio().expect("ratio") - 0.5).abs() < 0.01, "ratio_half", passed, failed);
    pot.set_resistance_wa(30000.0).expect("wa");
    check_true!((pot.get_resistance_wa().expect("wa") - 30000.0).abs() < 500.0, "set_resistance_wa", passed, failed);
    pot.set_wiper(100).expect("set");
    check_true!(pot.increment(5).expect("inc") == 105 && pot.get_wiper().expect("get") == 105, "increment", passed, failed);
    check_true!(pot.increment(-500).expect("inc") == 0, "increment_clamps", passed, failed);

    pot.set_wiper(77).expect("set");
    pot.shutdown().expect("shutdown");
    check_true!(pot.is_shutdown(), "is_shutdown", passed, failed);
    check_true!(pot.get_wiper().expect("get") == 77, "shutdown_keeps_wcr", passed, failed);
    pot.resume().expect("resume");
    check_true!(!pot.is_shutdown() && pot.get_wiper().expect("get") == 77, "resume", passed, failed);

    pot.set_wiper(10).expect("set");
    pot.midscale().expect("midscale");
    check_true!(pot.get_wiper().expect("get") == 128, "midscale", passed, failed);

    pot.set_wiper(1).expect("set");
    pot.reset().expect("reset");
    check_true!(pot.get_wiper().expect("get") == 128, "reset_midscale", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    if failed > 0 { std::process::exit(1); }
}
