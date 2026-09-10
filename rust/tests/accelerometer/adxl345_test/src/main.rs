use linux_embedded_hal::I2cdev;
use periph::chips::accelerometer::{Adxl345Minimal, Adxl345Full};

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
        .unwrap_or(0x53);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut accel = Adxl345Minimal::new(dev, addr, false).expect("init ADXL345");

    let (x, y, z) = accel.read().expect("read");
    check_true!(x.is_finite() && y.is_finite() && z.is_finite(), "read_returns_floats", passed, failed);
    let mag = (x * x + y * y + z * z).sqrt();
    check_true!(mag >= 0.5 && mag <= 1.5, "magnitude_near_1g", passed, failed);

    drop(accel);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut accel_full = Adxl345Full::new(dev, addr, false).expect("init ADXL345 Full");
    accel_full.set_range(4).expect("set_range");
    let (x, y, z) = accel_full.read().expect("read");
    check_true!(x.is_finite() && y.is_finite() && z.is_finite(), "read_after_set_range_4g", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    if failed > 0 { std::process::exit(1); }
}