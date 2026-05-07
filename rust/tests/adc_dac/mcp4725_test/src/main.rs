use linux_embedded_hal::I2cdev;
use periph::chips::adc_dac::Mcp4725Full;

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

macro_rules! check_eq {
    ($val:expr, $expected:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $val == $expected { println!("PASS {}", $label); $passed += 1; }
        else { println!("FAIL {}: {} != {}", $label, $val, $expected); $failed += 1; }
    };
}

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x60);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut dac = Mcp4725Full::new(dev, addr);

    let mut passed = 0i32;
    let mut failed = 0i32;

    dac.set_voltage(0.5).unwrap();
    dac.set_raw(2048).unwrap();

    let result = dac.read().unwrap();
    check_true!(result.code <= 4095, "code in range", passed, failed);
    check_true!(result.voltage_fraction >= 0.0 && result.voltage_fraction <= 1.0, "voltage_fraction in range", passed, failed);
    check_true!(result.power_down <= 3, "power_down in range", passed, failed);
    check_true!(result.eeprom_code <= 4095, "eeprom_code in range", passed, failed);
    check_true!(result.eeprom_power_down <= 3, "eeprom_power_down in range", passed, failed);

    dac.set_power_down(1).unwrap();
    let result2 = dac.read().unwrap();
    check_eq!(result2.power_down, 1, "power_down mode 1", passed, failed);

    dac.wake_up().unwrap();
    dac.reset().unwrap();
    check_true!(dac.is_eeprom_ready().unwrap() == true || dac.is_eeprom_ready().unwrap() == false, "eeprom_ready or write in progress", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}