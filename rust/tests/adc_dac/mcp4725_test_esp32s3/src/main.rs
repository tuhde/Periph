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

    dac.set_voltage(0.5).ok();
    dac.set_raw(2048).ok();

    let result = dac.read().ok();
    if let Some(r) = result {
        check_true!(r.code <= 4095, "code in range", passed, failed);
        check_true!(r.voltage_fraction >= 0.0 && r.voltage_fraction <= 1.0, "voltage_fraction in range", passed, failed);
        check_true!(r.power_down <= 3, "power_down in range", passed, failed);
        check_true!(r.eeprom_code <= 4095, "eeprom_code in range", passed, failed);
        check_true!(r.eeprom_power_down <= 3, "eeprom_power_down in range", passed, failed);
    } else {
        check_true!(false, "read success", passed, failed);
    }

    dac.set_power_down(1).ok();
    let result2 = dac.read().ok();
    if let Some(r) = result2 {
        check_true!(r.power_down == 1, "power_down mode 1", passed, failed);
    } else {
        check_true!(false, "read after pd", passed, failed);
    }

    check_true!(dac.wake_up().is_ok(), "wake_up", passed, failed);
    check_true!(dac.reset().is_ok(), "reset", passed, failed);
    check_true!(dac.is_eeprom_ready().is_ok(), "is_eeprom_ready", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}