use linux_embedded_hal::I2cdev;
use periph::chips::gnss::{I2cBus, Neo6Minimal};

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

// Requires a NEO-6 module wired to I2C (DDC) with a clear sky view.
// Achieving an actual fix needs an outdoor antenna and can take up to
// ~26 s (cold start); this test only requires that well-typed values
// come back.
fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x42);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut gps = Neo6Minimal::new(I2cBus { i2c: dev, addr });

    check_true!(gps.fix() == 0, "fix_starts_at_0", passed, failed);
    check_true!(gps.latitude().is_none(), "latitude_starts_at_none", passed, failed);

    for _ in 0..3000 {
        let _ = gps.update();
    }

    check_true!(gps.fix() <= 2, "fix_is_valid_quality_code", passed, failed);
    if gps.fix() > 0 {
        let lat = gps.latitude().unwrap();
        let lon = gps.longitude().unwrap();
        check_true!(lat >= -90.0 && lat <= 90.0, "latitude_in_range_once_fixed", passed, failed);
        check_true!(lon >= -180.0 && lon <= 180.0, "longitude_in_range_once_fixed", passed, failed);
        check_true!(gps.altitude().is_some(), "altitude_populated_once_fixed", passed, failed);
    } else {
        println!("note: no fix acquired during the test window (needs sky view)");
    }

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
