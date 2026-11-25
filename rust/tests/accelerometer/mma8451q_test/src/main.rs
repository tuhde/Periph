use linux_embedded_hal::I2cdev;
use periph::chips::accelerometer::Mma8451QMinimal;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x1C);

    let dev  = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Mma8451QMinimal::new(dev, addr).expect("init MMA8451Q");

    let mut passed = 0i32;
    let mut failed = 0i32;
    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { println!("PASS {}", $label); passed += 1; }
            else      { println!("FAIL {}", $label); failed += 1; }
        };
    }

    // Wait for the STANDBY → ACTIVE turn-on time.
    std::thread::sleep(Duration::from_millis(22));

    let (x, y, z) = chip.read().expect("read");
    check_true!(x.is_finite() && y.is_finite() && z.is_finite(), "read_returns_floats");
    let mag = (x * x + y * y + z * z).sqrt();
    check_true!(mag >= 0.5 && mag <= 1.5, "magnitude_near_1g");

    let (rx, ry, rz) = chip.read_raw().expect("read_raw");
    check_true!(rx >= -8192 && rx <= 8191, "raw_x_in_range");
    check_true!(ry >= -8192 && ry <= 8191, "raw_y_in_range");
    check_true!(rz >= -8192 && rz <= 8191, "raw_z_in_range");

    // The Full driver exercises the configuration and interrupt paths.
    let dev2 = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut full = periph::chips::accelerometer::Mma8451QFull::new(dev2, addr)
        .expect("init MMA8451Q Full");

    full.set_range(4).expect("set_range");
    std::thread::sleep(Duration::from_millis(22));
    let (x, y, z) = full.read().expect("read");
    check_true!(x.is_finite() && y.is_finite() && z.is_finite(), "read_after_set_range_4g");

    full.set_data_rate(50.0).expect("set_data_rate");
    std::thread::sleep(Duration::from_millis(50));
    let mode = full.read_system_mode().expect("read_system_mode");
    check_true!(mode <= 2, "system_mode_valid");

    let status = full.poll_interrupt().expect("poll_interrupt");
    check_true!(status as u16 <= 0xFF, "poll_interrupt_returns_byte");

    full.set_orientation(true, 100.0, 45, 4, 4, 1).expect("set_orientation");
    let (_changed, _lockout, _lapo, _back) = full.read_orientation().expect("read_orientation");
    check_true!(true, "orientation_readable");

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
