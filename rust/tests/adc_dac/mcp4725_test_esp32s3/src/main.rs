#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::adc_dac::Mcp4725Full;

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

    let mut dac = match Mcp4725Full::new(i2c, TEST_ADDR) {
        Ok(d) => d,
        Err(_) => {
            println!("FAIL init: could not reach MCP4725 at 0x{:02X}", TEST_ADDR);
            println!("===DONE: 0 passed, 1 failed===");
            loop {}
        }
    };

    let mut passed = 0i32;
    let mut failed = 0i32;

    dac.set_voltage(0.5).ok();
    check_true!(true, "set_voltage_0.5", passed, failed);

    dac.set_raw(2048).ok();
    check_true!(true, "set_raw_2048", passed, failed);

    dac.set_voltage_eeprom(0.5).ok();
    check_true!(true, "set_voltage_eeprom_0.5", passed, failed);

    dac.set_raw_eeprom(2048).ok();
    check_true!(true, "set_raw_eeprom_2048", passed, failed);

    let read_ok = dac.read().map(|(code, vf, _, ec, _, _)| code <= 4095 && vf >= 0.0 && vf <= 1.0 && ec <= 4095).unwrap_or(false);
    check_true!(read_ok, "read_values_valid", passed, failed);

    dac.set_power_down(0).ok();
    check_true!(true, "set_power_down_normal", passed, failed);

    dac.set_power_down(1).ok();
    check_true!(true, "set_power_down_1k", passed, failed);

    dac.set_power_down(2).ok();
    check_true!(true, "set_power_down_100k", passed, failed);

    dac.set_power_down(3).ok();
    check_true!(true, "set_power_down_500k", passed, failed);

    dac.wake_up().ok();
    check_true!(true, "wake_up", passed, failed);

    dac.reset().ok();
    check_true!(true, "reset", passed, failed);

    let ready_ok = dac.is_eeprom_ready().is_ok();
    check_true!(ready_ok, "is_eeprom_ready_ok", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}