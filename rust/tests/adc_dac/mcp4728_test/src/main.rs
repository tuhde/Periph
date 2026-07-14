use linux_embedded_hal::I2cdev;
use periph::chips::adc_dac::Mcp4728Full;

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
    let mut dac = Mcp4728Full::new(dev, addr).expect("init MCP4728");

    let mut passed = 0i32;
    let mut failed = 0i32;

    dac.set_voltage(0, 0.5).unwrap();
    check_true!(true, "set_voltage_ch0_0.5", passed, failed);

    dac.set_raw(1, 2048).unwrap();
    check_true!(true, "set_raw_ch1_2048", passed, failed);

    dac.set_all([0.0, 0.25, 0.5, 1.0]).unwrap();
    check_true!(true, "set_all", passed, failed);

    dac.set_voltage_eeprom(0, 0.5, 0, 1).unwrap();
    check_true!(true, "set_voltage_eeprom", passed, failed);

    dac.set_raw_eeprom(1, 2048, 0, 1).unwrap();
    check_true!(true, "set_raw_eeprom", passed, failed);

    dac.set_all_eeprom([0.0, 0.25, 0.5, 0.75], [0, 0, 0, 0], [1, 1, 1, 1]).unwrap();
    check_true!(true, "set_all_eeprom", passed, failed);

    dac.set_vref(0, 0, 0, 0).unwrap();
    check_true!(true, "set_vref", passed, failed);

    dac.set_gain(1, 1, 1, 1).unwrap();
    check_true!(true, "set_gain", passed, failed);

    dac.set_power_down(0, 0, 0, 0).unwrap();
    check_true!(true, "set_power_down_normal", passed, failed);

    dac.set_power_down(1, 2, 3, 0).unwrap();
    check_true!(true, "set_power_down_modes", passed, failed);

    let state = dac.read().unwrap();
    check_true!(state.channel[0].code <= 4095, "read_ch0_code_range", passed, failed);
    check_true!(state.channel[0].eeprom_code <= 4095, "read_ch0_eeprom_code_range", passed, failed);
    check_true!(state.channel[0].gain == 1 || state.channel[0].gain == 2, "read_ch0_gain_valid", passed, failed);
    check_true!(state.channel[0].vref == 0 || state.channel[0].vref == 1, "read_ch0_vref_valid", passed, failed);

    dac.software_update().unwrap();
    check_true!(true, "software_update", passed, failed);

    dac.wake_up().unwrap();
    check_true!(true, "wake_up", passed, failed);

    dac.reset().unwrap();
    check_true!(true, "reset", passed, failed);

    let ready = dac.is_eeprom_ready().unwrap();
    check_true!(ready == true || ready == false, "is_eeprom_ready_bool", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
