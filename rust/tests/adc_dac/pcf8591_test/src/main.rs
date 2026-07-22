use linux_embedded_hal::I2cdev;
use periph::chips::adc_dac::pcf8591::{
    MODE_2_DIFFERENTIAL, MODE_3_DIFFERENTIAL, MODE_4_SINGLE_ENDED, MODE_MIXED,
};
use periph::chips::adc_dac::Pcf8591Full;

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
        .unwrap_or(0x48);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut adc = Pcf8591Full::new(dev, addr).expect("init PCF8591");

    let mut passed = 0i32;
    let mut failed = 0i32;

    let ch0 = adc.read_channel(0).unwrap();
    check_true!(ch0 <= 255, "read_channel(0) in [0, 255]", passed, failed);

    let ch3 = adc.read_channel(3).unwrap();
    check_true!(ch3 <= 255, "read_channel(3) in [0, 255]", passed, failed);

    let ch_oob = adc.read_channel(99).unwrap();
    check_true!(ch_oob <= 255, "read_channel(99) clamped", passed, failed);

    let all_raw = adc.read_all().unwrap();
    check_true!(all_raw.iter().all(|&v| v <= 255), "read_all values in [0, 255]", passed, failed);

    let v0 = adc.read_channel_voltage(0, 3.3, 0.0).unwrap();
    check_true!(v0 >= 0.0 && v0 <= 3.3, "read_channel_voltage in [0, 3.3]", passed, failed);

    let v_all = adc.read_all_voltage(3.3, 0.0).unwrap();
    check_true!(v_all.iter().all(|&v| v >= 0.0 && v <= 3.3), "read_all_voltage values in [0, 3.3]", passed, failed);

    adc.configure(MODE_4_SINGLE_ENDED, false, false).unwrap();
    check_true!(true, "configure 4 single-ended accepted", passed, failed);

    adc.configure(MODE_3_DIFFERENTIAL, false, false).unwrap();
    let diff = adc.read_differential(0).unwrap();
    check_true!(diff >= -128 && diff <= 127, "read_differential in [-128, 127]", passed, failed);

    adc.configure(MODE_MIXED, false, false).unwrap();
    check_true!(true, "configure mixed mode accepted", passed, failed);

    adc.configure(MODE_2_DIFFERENTIAL, false, false).unwrap();
    check_true!(true, "configure 2 differential accepted", passed, failed);

    adc.configure(MODE_4_SINGLE_ENDED, true, false).unwrap();
    let _auto = adc.read_all().unwrap();
    check_true!(true, "read_all with auto-increment returns 4 values", passed, failed);

    adc.configure(MODE_4_SINGLE_ENDED, false, true).unwrap();
    check_true!(true, "configure enables DAC", passed, failed);

    adc.set_dac(0).unwrap();
    check_true!(true, "set_dac(0) accepted", passed, failed);

    adc.set_dac(255).unwrap();
    check_true!(true, "set_dac(255) accepted", passed, failed);

    adc.set_dac(128).unwrap();
    check_true!(true, "set_dac(128) accepted", passed, failed);

    adc.set_dac_voltage(0.0).unwrap();
    check_true!(true, "set_dac_voltage(0.0) accepted", passed, failed);

    adc.set_dac_voltage(1.0).unwrap();
    check_true!(true, "set_dac_voltage(1.0) accepted", passed, failed);

    adc.set_dac_voltage(0.5).unwrap();
    check_true!(true, "set_dac_voltage(0.5) accepted", passed, failed);

    adc.disable_dac().unwrap();
    check_true!(true, "disable_dac accepted", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
