use linux_embedded_hal::I2cdev;
use periph::chips::led::{HT16K33Full, HT16K33Minimal, COMMONS, ROWS};

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
        .unwrap_or(0x70);

    let mut passed = 0i32;
    let mut failed = 0i32;

    check_true!(COMMONS == 8 && ROWS == 16, "constants", passed, failed);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut matrix = HT16K33Minimal::new(dev, addr).expect("init HT16K33");
    check_true!(matrix.set_pixel(0, 0, true) && matrix.set_pixel(7, 15, true), "set_pixel", passed, failed);
    check_true!(!matrix.set_pixel(8, 0, true) && !matrix.set_pixel(0, 16, true), "set_pixel_range", passed, failed);
    check_true!(matrix.show().is_ok(), "show", passed, failed);
    check_true!(matrix.write_raw(&[0xFF, 0x00], 0).unwrap_or(false), "write_raw", passed, failed);
    check_true!(!matrix.write_raw(&[0, 0], 15).unwrap_or(true), "write_raw_overflow", passed, failed);
    check_true!(matrix.set_brightness(0).is_ok() && matrix.set_brightness(15).is_ok(), "set_brightness", passed, failed);
    check_true!(matrix.clear().is_ok(), "clear", passed, failed);
    let dev = matrix.release();

    let mut full = HT16K33Full::new(dev, addr).expect("init HT16K33");
    full.set_pixel(3, 4, true);
    full.show().expect("show");
    check_true!(full.get_pixel(3, 4) && !full.get_pixel(3, 5), "get_pixel", passed, failed);
    let blink_ok = full.set_blink(2.0).is_ok() && full.set_blink(1.0).is_ok()
        && full.set_blink(0.5).is_ok() && full.set_blink(0.0).is_ok();
    check_true!(blink_ok, "set_blink", passed, failed);
    check_true!(full.display_off().is_ok() && full.display_on().is_ok(), "display_off_on", passed, failed);
    check_true!(full.standby().is_ok() && full.wake().is_ok(), "standby_wake", passed, failed);
    let int_ok = full.set_int_output(true, false).is_ok() && full.set_int_output(true, true).is_ok()
        && full.set_int_output(false, false).is_ok();
    check_true!(int_ok, "set_int_output", passed, failed);
    check_true!(full.read_keys().is_ok(), "read_keys", passed, failed);
    check_true!(full.key_pressed().is_ok(), "key_pressed", passed, failed);
    let _ = full.clear();

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
