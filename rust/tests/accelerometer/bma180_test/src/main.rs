use linux_embedded_hal::I2cdev;
use periph::chips::accelerometer::Bma180Minimal;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x40);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Bma180Minimal::new(dev, addr).expect("init BMA180");

    let mut passed = 0i32;
    let mut failed = 0i32;
    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { println!("PASS {}", $label); passed += 1; }
            else      { println!("FAIL {}", $label); failed += 1; }
        };
    }

    let (x, y, z) = chip.read().expect("read");
    check_true!(x.is_finite() && y.is_finite() && z.is_finite(), "read_returns_floats");
    let mag = (x * x + y * y + z * z).sqrt();
    check_true!(mag >= 0.5 && mag <= 1.5, "magnitude_near_1g");

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip_full = periph::chips::accelerometer::Bma180Full::new(dev, addr).expect("init BMA180 full");
    chip_full.set_range(4.0).expect("set range");
    let (x, y, z) = chip_full.read().expect("read");
    check_true!(x.is_finite() && y.is_finite() && z.is_finite(), "read_after_set_range_4g");

    let temp = chip_full.read_temperature().expect("read temp");
    check_true!(temp >= -40.0 && temp <= 87.5, "temperature_in_range");

    let (al, ml) = chip_full.read_version().expect("read version");
    check_true!(al <= 0x0F && ml <= 0x0F, "read_version_ok");

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}