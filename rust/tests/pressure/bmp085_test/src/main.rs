use linux_embedded_hal::I2cdev;
use periph::chips::pressure::{Bmp085Minimal, Bmp085Full, BMP085_OSS_ULP, BMP085_OSS_HIGH_RES};

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
        .unwrap_or(0x77);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut bmp = Bmp085Minimal::new(dev, addr).expect("init BMP085");

    let t = bmp.temperature().unwrap();
    check_true!(t >= -40.0 && t <= 85.0, "temperature_range", passed, failed);

    let p = bmp.pressure().unwrap();
    check_true!(p >= 30000.0 && p <= 110000.0, "pressure_range", passed, failed);

    drop(bmp);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut bmp_full = Bmp085Full::new(dev, addr, BMP085_OSS_ULP).expect("init BMP085 Full");
    check_true!(bmp_full.oversampling() == BMP085_OSS_ULP, "default_oss", passed, failed);
    bmp_full.set_oversampling(BMP085_OSS_HIGH_RES);
    check_true!(bmp_full.oversampling() == BMP085_OSS_HIGH_RES, "set_oss", passed, failed);

    let alt = bmp_full.altitude(101325.0).unwrap_or(-1.0);
    check_true!(alt >= -500.0 && alt <= 9000.0, "altitude_range", passed, failed);

    let slp = bmp_full.sea_level_pressure(0.0).unwrap_or(0.0);
    check_true!(slp >= 90000.0 && slp <= 110000.0, "sea_level_pressure", passed, failed);

    check_true!(bmp_full.chip_id().unwrap() == 0x55, "chip_id", passed, failed);

    bmp_full.reset().unwrap();
    check_true!(true, "reset", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}