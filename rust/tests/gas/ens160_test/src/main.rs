use linux_embedded_hal::I2cdev;
use periph::chips::gas::{Ens160Minimal, Ens160Full};

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
        .unwrap_or(0x52);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut sensor = Ens160Minimal::new(dev, addr).expect("init ENS160");
    check_true!(true, "init", passed, failed);

    let status = sensor.status().unwrap();
    check_true!(status <= 3, "status_valid_range", passed, failed);

    println!("Waiting for warm-up (may take up to 3 minutes)...");
    let mut timeout = 180;
    while sensor.status().unwrap() != 0 && timeout > 0 {
        std::thread::sleep(std::time::Duration::from_secs(1));
        timeout -= 1;
    }
    if sensor.status().unwrap() == 0 {
        check_true!(true, "warmup_complete", passed, failed);
    } else {
        println!("FAIL warmup_timeout");
        failed += 1;
    }

    let (aqi, tvoc_ppb, eco2_ppm) = sensor.read_air_quality().unwrap();
    check_true!(aqi >= 1 && aqi <= 5, "aqi_range", passed, failed);
    check_true!(tvoc_ppb >= 0.0, "tvoc_non_negative", passed, failed);
    check_true!(eco2_ppm >= 400.0, "eco2_minimum", passed, failed);

    drop(sensor);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut sensor_full = Ens160Full::new(dev, addr).expect("init ENS160 Full");
    check_true!(true, "full_init", passed, failed);

    sensor_full.set_compensation(25.0, 50.0).unwrap();
    check_true!(true, "set_compensation", passed, failed);

    let tvoc = sensor_full.read_tvoc().unwrap();
    check_true!(tvoc >= 0.0, "read_tvoc", passed, failed);

    let eco2 = sensor_full.read_eco2().unwrap();
    check_true!(eco2 >= 400.0, "read_eco2", passed, failed);

    let aqi = sensor_full.read_aqi().unwrap();
    check_true!(aqi >= 1 && aqi <= 5, "read_aqi", passed, failed);

    let (temp_actual, rh_actual) = sensor_full.read_compensation_actuals().unwrap();
    check_true!(true, "read_compensation_actuals", passed, failed);

    let (major, minor, release) = sensor_full.get_firmware_version().unwrap();
    check_true!(true, "get_firmware_version", passed, failed);

    sensor_full.sleep().unwrap();
    check_true!(true, "sleep", passed, failed);
    std::thread::sleep(std::time::Duration::from_secs(1));
    sensor_full.wake().unwrap();
    check_true!(true, "wake", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
