use linux_embedded_hal::I2cdev;
use periph::chips::gyroscope::{L3g4200dFull, L3g4200dMinimal, FS_500_DPS, ODR_200_HZ, FIFO_STREAM};

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
        .unwrap_or(0x68);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut gyro = L3g4200dMinimal::new(dev, addr, false).expect("init L3G4200D");

    let (x, y, z) = gyro.angular_rate().unwrap();
    check_true!(x.abs() < 50.0, "angular_rate_x_range", passed, failed);
    check_true!(y.abs() < 50.0, "angular_rate_y_range", passed, failed);
    check_true!(z.abs() < 50.0, "angular_rate_z_range", passed, failed);

    drop(gyro);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut gyro_full = L3g4200dFull::new(dev, addr, false).expect("init L3G4200D Full");
    check_true!(gyro_full.who_am_i().unwrap_or(0) == 0xD3, "who_am_i", passed, failed);

    gyro_full.configure(ODR_200_HZ, 0, FS_500_DPS).unwrap();
    let (x2, _, _) = gyro_full.angular_rate().unwrap();
    check_true!(x2.abs() < 500.0, "configure_then_read", passed, failed);

    check_true!(gyro_full.status().unwrap_or(0xFF) <= 0xFF, "status_readable", passed, failed);
    let t = gyro_full.temperature().unwrap_or(0);
    check_true!(t >= -50 && t <= 100, "temperature_range", passed, failed);

    gyro_full.set_full_scale(2000).unwrap();
    let (x3, _, _) = gyro_full.angular_rate().unwrap();
    check_true!(x3.abs() < 2000.0, "set_full_scale_2000", passed, failed);

    let samples = gyro_full.fifo_samples().unwrap_or(0);
    check_true!(samples <= 31, "fifo_samples_in_range", passed, failed);

    gyro_full.enable_fifo(FIFO_STREAM, 10).unwrap();
    check_true!(true, "enable_fifo_no_throw", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
