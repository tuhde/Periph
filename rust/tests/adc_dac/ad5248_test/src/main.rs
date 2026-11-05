use linux_embedded_hal::I2cdev;
use periph::chips::adc_dac::Ad5248Full;

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
        .unwrap_or(0x2C);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Ad5248Full::new(dev, addr, 10000.0, 160.0);
    let mut passed = 0i32;
    let mut failed = 0i32;

    // --- Wiper write / readback ---
    chip.set_wiper(0, 100).ok();
    check_true!(chip.get_wiper(0).map(|v| v == 100).unwrap_or(false), "set/get wiper ch0", passed, failed);
    chip.set_wiper(1, 200).ok();
    check_true!(chip.get_wiper(1).map(|v| v == 200).unwrap_or(false), "set/get wiper ch1", passed, failed);
    check_true!(chip.get_wiper(0).map(|v| v == 100).unwrap_or(false), "ch0 unchanged by ch1 write", passed, failed);

    // --- Resistance ---
    chip.set_resistance(0, 5320.0).ok();
    check_true!(chip.get_wiper(0).map(|v| v == 128).unwrap_or(false), "set_resistance midscale code", passed, failed);
    check_true!(chip.get_resistance(0).map(|r| (r - 5320.0).abs() < 1.0).unwrap_or(false), "get_resistance ~5320", passed, failed);

    // --- Ratio / increment / both ---
    chip.set_both(10, 20).ok();
    check_true!(chip.get_wiper(0).map(|v| v == 10).unwrap_or(false), "set_both ch0", passed, failed);
    check_true!(chip.get_wiper(1).map(|v| v == 20).unwrap_or(false), "set_both ch1", passed, failed);
    chip.set_ratio(0, 0.5).ok();
    check_true!(chip.get_ratio(0).map(|r| (r - 0.5).abs() < 0.01).unwrap_or(false), "get_ratio ~0.5", passed, failed);
    chip.set_wiper(0, 250).ok();
    check_true!(chip.increment(0, 10).map(|v| v == 255).unwrap_or(false), "increment clamps", passed, failed);

    // --- Shutdown keeps RDAC ---
    chip.set_wiper(1, 77).ok();
    chip.shutdown(1).ok();
    check_true!(chip.is_shutdown(1), "is_shutdown", passed, failed);
    chip.resume(1).ok();
    check_true!(chip.get_wiper(1).map(|v| v == 77).unwrap_or(false), "RDAC kept across shutdown", passed, failed);

    // --- Reset ---
    chip.reset().ok();
    check_true!(chip.get_wiper(0).map(|v| v == 0x80).unwrap_or(false), "reset ch0", passed, failed);
    check_true!(chip.get_wiper(1).map(|v| v == 0x80).unwrap_or(false), "reset ch1", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
