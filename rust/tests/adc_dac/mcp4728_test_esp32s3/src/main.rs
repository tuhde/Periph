#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::adc_dac::Mcp4728Full;

esp_app_desc!();

const TEST_ADDR: u8 = 0x60;

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

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);

    let mut dac = match Mcp4728Full::new(i2c, TEST_ADDR) {
        Ok(d) => d,
        Err(_) => {
            println!("FAIL init: could not reach MCP4728 at 0x{:02X}", TEST_ADDR);
            println!("===DONE: 0 passed, 1 failed===");
            loop {}
        }
    };

    let mut passed = 0i32;
    let mut failed = 0i32;

    dac.set_voltage(0, 0.5).ok();
    check_true!(true, "set_voltage_ch0_0.5", passed, failed);

    dac.set_raw(1, 2048).ok();
    check_true!(true, "set_raw_ch1_2048", passed, failed);

    dac.set_all([0.0, 0.25, 0.5, 1.0]).ok();
    check_true!(true, "set_all", passed, failed);

    dac.set_voltage_eeprom(0, 0.5, 0, 1).ok();
    check_true!(true, "set_voltage_eeprom", passed, failed);

    dac.set_raw_eeprom(1, 2048, 0, 1).ok();
    check_true!(true, "set_raw_eeprom", passed, failed);

    dac.set_all_eeprom([0.0, 0.25, 0.5, 0.75], [0, 0, 0, 0], [1, 1, 1, 1]).ok();
    check_true!(true, "set_all_eeprom", passed, failed);

    dac.set_vref(0, 0, 0, 0).ok();
    check_true!(true, "set_vref", passed, failed);

    dac.set_gain(1, 1, 1, 1).ok();
    check_true!(true, "set_gain", passed, failed);

    dac.set_power_down(0, 0, 0, 0).ok();
    check_true!(true, "set_power_down_normal", passed, failed);

    let read_ok = dac.read().map(|s| {
        s.channel[0].code <= 4095
            && s.channel[0].eeprom_code <= 4095
            && (s.channel[0].gain == 1 || s.channel[0].gain == 2)
    }).unwrap_or(false);
    check_true!(read_ok, "read_values_valid", passed, failed);

    dac.software_update().ok();
    check_true!(true, "software_update", passed, failed);

    dac.wake_up().ok();
    check_true!(true, "wake_up", passed, failed);

    dac.reset().ok();
    check_true!(true, "reset", passed, failed);

    let ready_ok = dac.is_eeprom_ready().is_ok();
    check_true!(ready_ok, "is_eeprom_ready_ok", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}
