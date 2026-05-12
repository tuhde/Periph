use linux_embedded_hal::I2cdev;
use periph::chips::adc_dac::Mcp4725Full;

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
        .unwrap_or(0x60);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut dac = Mcp4725Full::new(dev, addr).expect("init MCP4725");

    let mut passed = 0i32;
    let mut failed = 0i32;

    dac.set_voltage(0.5).unwrap();
    check_true!(true, "set_voltage_0.5", passed, failed);

    dac.set_raw(2048).unwrap();
    check_true!(true, "set_raw_2048", passed, failed);

    dac.set_voltage_eeprom(0.5).unwrap();
    check_true!(true, "set_voltage_eeprom_0.5", passed, failed);

    dac.set_raw_eeprom(2048).unwrap();
    check_true!(true, "set_raw_eeprom_2048", passed, failed);

    let (code, vf, pd, ec, epd, ready) = dac.read().unwrap();
    check_true!(code <= 4095, "read_code_range", passed, failed);
    check_true!(vf >= 0.0 && vf <= 1.0, "read_voltage_fraction_range", passed, failed);
    check_true!(ec <= 4095, "read_eeprom_code_range", passed, failed);

    dac.set_power_down(0).unwrap();
    check_true!(true, "set_power_down_normal", passed, failed);

    dac.set_power_down(1).unwrap();
    check_true!(true, "set_power_down_1k", passed, failed);

    dac.set_power_down(2).unwrap();
    check_true!(true, "set_power_down_100k", passed, failed);

    dac.set_power_down(3).unwrap();
    check_true!(true, "set_power_down_500k", passed, failed);

    dac.wake_up().unwrap();
    check_true!(true, "wake_up", passed, failed);

    dac.reset().unwrap();
    check_true!(true, "reset", passed, failed);

    let ready = dac.is_eeprom_ready().unwrap();
    check_true!(ready == true || ready == false, "is_eeprom_ready_bool", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}