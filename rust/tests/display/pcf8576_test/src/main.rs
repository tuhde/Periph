use linux_embedded_hal::I2cdev;
use periph::chips::display::{
    Pcf8576Minimal, Pcf8576Full, SEVEN_SEG,
    BACKPLANES_1, BACKPLANES_2, BACKPLANES_3, BACKPLANES_4, BIAS_1_2, BIAS_1_3,
    BLINK_OFF, BLINK_2_HZ, BANK_0, BANK_1,
};

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
    let mut lcd = Pcf8576Minimal::new(dev, addr).expect("init PCF8576");
    lcd.clear().expect("clear");
    check_true!(true, "clear", passed, failed);

    let bytes = [0xED, 0x60, 0xA7, 0xE3];
    lcd.write_raw(0, &bytes).expect("write_raw");
    check_true!(true, "write_raw", passed, failed);

    lcd.set_digit_7seg(0, 0xED).expect("set_digit_7seg");
    check_true!(true, "set_digit_7seg", passed, failed);

    check_true!(SEVEN_SEG[0] == 0xED, "seven_seg_0", passed, failed);
    check_true!(SEVEN_SEG[9] == 0xEB, "seven_seg_9", passed, failed);

    drop(lcd);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut lcd_full = Pcf8576Full::new(dev, addr).expect("init PCF8576 Full");

    lcd_full.enable().expect("enable");
    lcd_full.disable().expect("disable");
    lcd_full.enable().expect("enable again");
    check_true!(true, "enable_disable", passed, failed);

    lcd_full.set_mode(BACKPLANES_4, BIAS_1_3).expect("set_mode_4");
    lcd_full.set_mode(BACKPLANES_2, BIAS_1_2).expect("set_mode_2");
    lcd_full.set_mode(BACKPLANES_1, BIAS_1_3).expect("set_mode_1");
    check_true!(true, "set_mode", passed, failed);

    lcd_full.set_blink(BLINK_2_HZ, false).expect("set_blink_2hz");
    lcd_full.set_blink(BLINK_OFF, true).expect("set_blink_off");
    check_true!(true, "set_blink", passed, failed);

    lcd_full.set_bank(BANK_0, BANK_0).expect("set_bank_0_0");
    lcd_full.set_bank(BANK_1, BANK_1).expect("set_bank_1_1");
    check_true!(true, "set_bank", passed, failed);

    lcd_full.device_select(0).expect("device_select_0");
    lcd_full.device_select(7).expect("device_select_7");
    check_true!(true, "device_select", passed, failed);

    let _ = BACKPLANES_3;
    let _ = BACKPLANES_4;
    let _ = BIAS_1_2;
    let _ = BIAS_1_3;
    let _ = BLINK_2_HZ;
    let _ = BLINK_OFF;
    let _ = BANK_0;
    let _ = BANK_1;

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
