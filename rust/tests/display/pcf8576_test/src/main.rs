use linux_embedded_hal::I2cdev;
use periph::chips::display::{Pcf8576Full, SEG_7SEG, BLINK_OFF, BLINK_1HZ, MUX_1_4};

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
    let mut chip = Pcf8576Full::new(dev, addr).expect("init PCF8576");

    let mut passed = 0i32;
    let mut failed = 0i32;

    chip.clear().expect("clear");
    check_true!(true, "clear_no_exception", passed, failed);

    chip.set_digit_7seg(0, SEG_7SEG[0]).expect("set_digit_7seg");
    check_true!(true, "set_digit_7seg_no_exception", passed, failed);

    let data: [u8; 2] = [0xED, 0x60];
    chip.write_raw(0, &data).expect("write_raw");
    check_true!(true, "write_raw_no_exception", passed, failed);

    chip.enable().expect("enable");
    check_true!(true, "enable_no_exception", passed, failed);

    chip.disable().expect("disable");
    check_true!(true, "disable_no_exception", passed, failed);

    chip.set_mode(MUX_1_4, 0).expect("set_mode");
    check_true!(true, "set_mode_no_exception", passed, failed);

    chip.set_blink(BLINK_OFF, false).expect("set_blink");
    check_true!(true, "set_blink_no_exception", passed, failed);

    chip.set_bank(0, 0).expect("set_bank");
    check_true!(true, "set_bank_no_exception", passed, failed);

    chip.device_select(0).expect("device_select");
    check_true!(true, "device_select_no_exception", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}