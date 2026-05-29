use linux_embedded_hal::I2cdev;
use linux_embedded_hal::Delay;
use periph::chips::environmental::Aht21Full;

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
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
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x38);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut chip = Aht21Full::new(dev, addr, &mut delay).expect("init AHT21");

    let mut passed = 0i32;
    let mut failed = 0i32;

    check_true!(chip.is_calibrated().unwrap(), "is_calibrated", passed, failed);
    check_true!(!chip.is_busy().unwrap(), "not_busy_at_idle", passed, failed);

    let (t, h) = chip.read(&mut delay).unwrap();
    check_true!(t >= -40.0 && t <= 120.0, "temperature_range", passed, failed);
    check_true!(h >= 0.0 && h <= 100.0, "humidity_range", passed, failed);

    let tr = chip.read_temperature(&mut delay).unwrap();
    check_true!(tr >= -40.0 && tr <= 120.0, "read_temperature_range", passed, failed);

    let hr = chip.read_humidity(&mut delay).unwrap();
    check_true!(hr >= 0.0 && hr <= 100.0, "read_humidity_range", passed, failed);

    let (tc, hc, crc_ok) = chip.read_with_crc(&mut delay).unwrap();
    check_true!(crc_ok, "crc_ok", passed, failed);
    check_true!(tc >= -40.0 && tc <= 120.0, "crc_temperature_range", passed, failed);
    check_true!(hc >= 0.0 && hc <= 100.0, "crc_humidity_range", passed, failed);

    chip.soft_reset(&mut delay).unwrap();
    std::thread::sleep(std::time::Duration::from_millis(50));
    check_true!(chip.is_calibrated().unwrap(), "calibrated_after_reset", passed, failed);

    let (t2, h2) = chip.read(&mut delay).unwrap();
    check_true!(t2 >= -40.0 && t2 <= 120.0, "read_after_reset_temperature", passed, failed);
    check_true!(h2 >= 0.0 && h2 <= 100.0, "read_after_reset_humidity", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
