use linux_embedded_hal::I2cdev;
use periph::chips::pressure::{
    Bmp280Full, OSRS_X1, OSRS_X2, OSRS_X4, MODE_FORCED, FILTER_OFF, FILTER_4, T_SB_62_5_MS,
};

macro_rules! check {
    ($label:expr, $cond:expr, $passed:expr, $failed:expr) => {
        if $cond {
            println!("PASS {}", $label);
            $passed += 1;
        } else {
            println!("FAIL {}", $label);
            $failed += 1;
        }
    };
}

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS")
        .ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok().and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x76);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut sensor = Bmp280Full::new(dev, addr, OSRS_X1, OSRS_X1, MODE_FORCED, FILTER_OFF, 0)
        .expect("init BMP280");

    let mut passed = 0u32;
    let mut failed = 0u32;

    // chip_id — 0x58 = BMP280, 0x60 = BME280 (same P/T interface)
    let cid = sensor.chip_id().expect("chip_id");
    check!("chip_id", cid == 0x58 || cid == 0x60, passed, failed);

    // temperature in sensor operating range
    let t = sensor.temperature().expect("temperature");
    check!("temperature_range", t >= -40.0 && t <= 85.0, passed, failed);

    // pressure in datasheet valid range
    let p = sensor.pressure().expect("pressure");
    check!("pressure_range", p >= 300.0 && p <= 1100.0, passed, failed);

    // altitude returns a finite value
    let alt = sensor.altitude(1013.25).expect("altitude");
    check!("altitude_finite", alt.is_finite(), passed, failed);

    // sea_level_pressure returns a plausible value
    let slp = sensor.sea_level_pressure(0.0).expect("sea_level_pressure");
    check!("sea_level_pressure_range", slp >= 300.0 && slp <= 1100.0, passed, failed);

    // configure
    sensor.configure(OSRS_X2, OSRS_X4, MODE_FORCED, FILTER_4, T_SB_62_5_MS).expect("configure");
    check!("configure", true, passed, failed);

    // set_oversampling
    sensor.set_oversampling(OSRS_X1, OSRS_X1).expect("set_oversampling");
    check!("set_oversampling", true, passed, failed);

    // set_filter
    sensor.set_filter(FILTER_OFF).expect("set_filter");
    check!("set_filter", true, passed, failed);

    // reset — sensor must remain functional
    sensor.reset().expect("reset");
    check!("reset", true, passed, failed);

    let t_after = sensor.temperature().expect("temperature after reset");
    check!("temperature_after_reset", t_after >= -40.0 && t_after <= 85.0, passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
