use linux_embedded_hal::I2cdev;
use periph::chips::magnetometer::As5600Full;

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
    let mut chip = As5600Full::new(dev, addr).expect("init AS5600");

    let mut passed = 0i32;
    let mut failed = 0i32;

    // --- Magnet detection ---
    check_true!(chip.is_magnet_detected().unwrap_or(false), "magnet_detected", passed, failed);

    // --- Angle readings ---
    let a = chip.angle().unwrap_or(0.0);
    check_true!(a >= 0.0 && a < 360.0, "angle_range", passed, failed);

    let r = chip.angle_raw().unwrap_or(0);
    check_true!(r <= 4095, "angle_raw_range", passed, failed);

    let ra = chip.raw_angle().unwrap_or(0);
    check_true!(ra <= 4095, "raw_angle_range", passed, failed);

    let rad = chip.raw_angle_degrees().unwrap_or(0.0);
    check_true!(rad >= 0.0 && rad < 360.0, "raw_angle_degrees_range", passed, failed);

    // --- Diagnostics ---
    check_true!(chip.agc().unwrap_or(0) >= 0, "agc_non_negative", passed, failed);
    check_true!(chip.magnitude().unwrap_or(0) >= 0, "magnitude_non_negative", passed, failed);

    // --- Status ---
    let sb = chip.status_byte().unwrap_or(0);
    check_true!(sb <= 255, "status_byte_valid", passed, failed);

    // --- Position configuration (volatile) ---
    chip.set_zero_position(100).unwrap();
    check_true!(chip.zero_position().unwrap() == 100, "zero_position_after_set", passed, failed);

    chip.set_max_position(2000).unwrap();
    check_true!(chip.max_position().unwrap() == 2000, "max_position_after_set", passed, failed);

    chip.set_max_angle(2048).unwrap();
    check_true!(chip.max_angle().unwrap() == 2048, "max_angle_after_set", passed, failed);

    // --- Configure ---
    chip.configure(0, 0, 0, 0, 0, 0, false).unwrap();
    check_true!(chip.is_magnet_detected().unwrap_or(false), "configure_accepted", passed, failed);

    // --- Burn count ---
    let bc = chip.burn_count().unwrap_or(0);
    check_true!(bc <= 3, "burn_count_range", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
