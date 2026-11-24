use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::led::SAA1064Full;

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
        .unwrap_or(0x38);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }
    let mut chip = SAA1064Full::new(dev, addr).expect("new");

    check_true!(chip.show_digits([Some(1), Some(2), Some(3), Some(4)]).is_ok(), "show_digits", passed, failed);
    check_true!(chip.show_number(1234).is_ok(), "show_number", passed, failed);
    check_true!(chip.write_raw(&[0x7F, 0x7F], 3).is_ok(), "write_raw", passed, failed);
    check_true!(chip.clear().is_ok(), "clear", passed, failed);
    check_true!(chip.set_mode(false).is_ok() && chip.set_mode(true).is_ok(), "set_mode", passed, failed);
    check_true!(chip.blank(true, false).is_ok() && chip.blank(false, true).is_ok() && chip.blank(false, false).is_ok(), "blank", passed, failed);
    check_true!(chip.set_current(3).is_ok() && chip.set_current(21).is_ok() && chip.set_current(12).is_ok(), "set_current", passed, failed);
    check_true!(chip.segment_test(true).is_ok() && chip.segment_test(false).is_ok(), "segment_test", passed, failed);
    check_true!(chip.set_segment_map([0, 1, 2, 3, 4, 5, 6, 7]), "set_segment_map", passed, failed);
    check_true!(chip.show_digit(1, Some(5), true).is_ok(), "show_digit", passed, failed);
    check_true!(chip.set_decimal_point(1, false).is_ok() && chip.set_decimal_point(4, true).is_ok(), "set_decimal_point", passed, failed);
    check_true!(chip.read_status().is_ok(), "read_status", passed, failed);
    check_true!(matches!(chip.power_reset_flag(), Ok(false)), "power_reset_flag_cleared", passed, failed);
    check_true!(chip.write_control(0x47).is_ok(), "write_control", passed, failed);
    let _ = chip.clear();

    println!("===DONE: {} passed, {} failed===", passed, failed);
    if failed > 0 { std::process::exit(1); }
}
