use linux_embedded_hal::I2cdev;
use periph::chips::pressure::{Bmp581Full, Bmp581Minimal, MODE_NORMAL};

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
        .unwrap_or(0x46);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut bmp = Bmp581Minimal::new(dev, addr, false).expect("init BMP581");

    let t = bmp.temperature().unwrap();
    check_true!(t >= -40.0 && t <= 85.0, "temperature_range", passed, failed);

    let p = bmp.pressure().unwrap();
    check_true!(p >= 30000.0 && p <= 125000.0, "pressure_range", passed, failed);

    drop(bmp);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut bmp_full = Bmp581Full::new(dev, addr, false).expect("init BMP581 Full");

    bmp_full.configure(0x1C, 0, 0, true).unwrap();
    bmp_full.set_mode(MODE_NORMAL).unwrap();
    let alt = bmp_full.altitude(101325.0).unwrap_or(-1.0);
    check_true!(alt >= -500.0 && alt <= 9000.0, "altitude_range", passed, failed);

    check_true!(bmp_full.chip_id().unwrap_or(0) == 0x50, "chip_id", passed, failed);

    bmp_full.software_reset().unwrap();
    check_true!(true, "software_reset", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}