use linux_embedded_hal::I2cdev;
use periph::chips::accelerometer::Bma150Minimal;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x38);

    let dev  = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Bma150Minimal::new(dev, addr).expect("init BMA150");

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

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
