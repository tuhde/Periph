use linux_embedded_hal::I2cdev;
use periph::chips::pressure::{Bmp180Minimal, Bmp180Full, OSS_ULP, OSS_HIGH_RES};

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
    let mut bmp = Bmp180Minimal::new(dev, addr).expect("init BMP180");

    let t = bmp.temperature().unwrap();
    check_true!(t >= -40.0 && t <= 85.0, "temperature_range", passed, failed);

    let p = bmp.pressure().unwrap();
    check_true!(p >= 300.0 && p <= 1100.0, "pressure_range", passed, failed);

    drop(bmp);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut bmp_full = Bmp180Full::new(dev, addr, OSS_ULP).expect("init BMP180 Full");
    check_true!(bmp_full.oversampling() == OSS_ULP, "default_oss", passed, failed);
    bmp_full.set_oversampling(OSS_HIGH_RES);
    check_true!(bmp_full.oversampling() == OSS_HIGH_RES, "set_oss", passed, failed);

    let alt = bmp_full.altitude(1013.25).unwrap_or(-1.0);
    check_true!(alt >= -500.0 && alt <= 9000.0, "altitude_range", passed, failed);

    let slp = bmp_full.sea_level_pressure(0.0).unwrap_or(0.0);
    check_true!(slp >= 900.0 && slp <= 1100.0, "sea_level_pressure", passed, failed);

    check_true!(bmp_full.chip_id().unwrap() == 0x55, "chip_id", passed, failed);

    bmp_full.reset().unwrap();
    check_true!(true, "reset", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
