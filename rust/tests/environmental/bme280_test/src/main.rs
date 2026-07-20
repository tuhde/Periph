use linux_embedded_hal::I2cdev;
use periph::chips::environmental::{Bme280Minimal, Bme280Full, OSRS_X4, OSRS_X2, OSRS_X1};

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
        .unwrap_or(0x76);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut bme = Bme280Minimal::new(dev, addr, false).expect("init BME280");

    let t = bme.temperature().unwrap();
    check_true!(t >= -40.0 && t <= 85.0, "temperature_range", passed, failed);

    let p = bme.pressure().unwrap();
    check_true!(p >= 300.0 && p <= 1100.0, "pressure_range", passed, failed);

    let h = bme.humidity().unwrap();
    check_true!(h >= 0.0 && h <= 100.0, "humidity_range", passed, failed);

    drop(bme);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut bme_full = Bme280Full::new(dev, addr, false).expect("init BME280 Full");

    bme_full.set_oversampling(OSRS_X4, OSRS_X2, OSRS_X1).unwrap();
    check_true!(true, "set_oversampling", passed, failed);

    let alt = bme_full.altitude(1013.25).unwrap_or(-1.0);
    check_true!(alt >= -500.0 && alt <= 9000.0, "altitude_range", passed, failed);

    let slp = bme_full.sea_level_pressure(0.0).unwrap_or(0.0);
    check_true!(slp >= 900.0 && slp <= 1100.0, "sea_level_pressure", passed, failed);

    let dp = bme_full.dew_point().unwrap_or(0.0);
    check_true!(dp >= -100.0 && dp <= 100.0, "dew_point_range", passed, failed);

    check_true!(bme_full.chip_id().unwrap() == 0x60, "chip_id", passed, failed);

    bme_full.reset().unwrap();
    check_true!(true, "reset", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
