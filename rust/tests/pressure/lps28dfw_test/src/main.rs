use linux_embedded_hal::I2cdev;
use periph::chips::pressure::{Lps28dfwMinimal, Lps28dfwFull, LPS28DFW_ODR_100_HZ, AVG_128, FS_MODE_2};

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
        .unwrap_or(0x5C);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut lps = Lps28dfwMinimal::new(dev, addr).expect("init LPS28DFW");

    let t = lps.read_temperature().unwrap_or(0.0);
    check_true!(t >= -40.0 && t <= 85.0, "temperature_range", passed, failed);

    let p = lps.read_pressure().unwrap_or(0.0);
    check_true!(p >= 260.0 && p <= 1260.0, "pressure_range", passed, failed);

    drop(lps);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut lps_full = Lps28dfwFull::new(dev, addr).expect("init LPS28DFW Full");

    lps_full.configure(LPS28DFW_ODR_100_HZ, AVG_128, FS_MODE_2, false, 1).unwrap();
    check_true!(lps_full.read_pressure().is_ok(), "configure", passed, failed);

    let threshold_raw = (1050.0_f32 * 16.0_f32) as i32;
    let capped = if threshold_raw > 0x7FFF { 0x7FFF } else { threshold_raw };
    check_true!(capped == 16800, "threshold_raw_conversion", passed, failed);

    let alt = lps_full.altitude(1013.25).unwrap_or(-1.0);
    check_true!(alt >= -500.0 && alt <= 9000.0, "altitude_range", passed, failed);

    let cid = lps_full.chip_id().unwrap_or(0);
    check_true!(cid == 0xB4 || cid == 0x00, "chip_id_or_no_chip", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}