use linux_embedded_hal::I2cdev;
use periph::chips::environmental::{Bme680Minimal, Bme680Full, OSRS_X4, OSRS_X2, OSRS_X1};

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
    let mut bme = Bme680Minimal::new(dev, addr).expect("init BME680");

    let t = bme.temperature().unwrap();
    check_true!(t >= -40.0 && t <= 85.0, "temperature_range", passed, failed);

    let p = bme.pressure().unwrap();
    check_true!(p >= 300.0 && p <= 1100.0, "pressure_range", passed, failed);

    let h = bme.humidity().unwrap();
    check_true!(h >= 0.0 && h <= 100.0, "humidity_range", passed, failed);

    drop(bme);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut bme_full = Bme680Full::new(dev, addr).expect("init BME680 Full");

    bme_full.set_oversampling(OSRS_X4, OSRS_X2, OSRS_X1).unwrap();
    check_true!(true, "set_oversampling", passed, failed);

    check_true!(bme_full.chip_id().unwrap() == 0x61, "chip_id", passed, failed);

    let (t2, p2, h2, _g2) = bme_full.read_all().unwrap();
    check_true!(t2 >= -40.0 && t2 <= 85.0 && p2 >= 300.0 && p2 <= 1100.0 && h2 >= 0.0 && h2 <= 100.0, "read_all", passed, failed);

    bme_full.reset().unwrap();
    check_true!(true, "reset", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
