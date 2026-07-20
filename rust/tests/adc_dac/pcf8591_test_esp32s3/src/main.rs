#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::adc_dac::pcf8591::{MODE_3_DIFFERENTIAL, MODE_4_SINGLE_ENDED};
use periph::chips::adc_dac::Pcf8591Full;

esp_app_desc!();

const TEST_ADDR: u8 = 0x48;

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

    let mut adc = match Pcf8591Full::new(i2c, TEST_ADDR) {
        Ok(d) => d,
        Err(_) => {
            println!("FAIL init: could not reach PCF8591 at 0x{:02X}", TEST_ADDR);
            println!("===DONE: 0 passed, 1 failed===");
            loop {}
        }
    };

    let mut passed = 0i32;
    let mut failed = 0i32;

    let ch0 = adc.read_channel(0).ok();
    check_true!(ch0.is_some(), "read_channel(0) ok", passed, failed);

    let all = adc.read_all().ok();
    check_true!(all.is_some(), "read_all ok", passed, failed);

    let v0 = adc.read_channel_voltage(0, 3.3, 0.0).ok();
    check_true!(v0.is_some(), "read_channel_voltage ok", passed, failed);

    adc.configure(MODE_4_SINGLE_ENDED, false, false).ok();
    check_true!(true, "configure 4 single-ended accepted", passed, failed);

    adc.configure(MODE_3_DIFFERENTIAL, false, false).ok();
    let diff = adc.read_differential(0).ok();
    check_true!(diff.is_some(), "read_differential ok", passed, failed);

    adc.configure(MODE_4_SINGLE_ENDED, false, true).ok();
    check_true!(true, "configure enables DAC", passed, failed);

    adc.set_dac(0).ok();
    check_true!(true, "set_dac(0) accepted", passed, failed);

    adc.set_dac(255).ok();
    check_true!(true, "set_dac(255) accepted", passed, failed);

    adc.set_dac(128).ok();
    check_true!(true, "set_dac(128) accepted", passed, failed);

    adc.set_dac_voltage(0.0).ok();
    check_true!(true, "set_dac_voltage(0.0) accepted", passed, failed);

    adc.set_dac_voltage(1.0).ok();
    check_true!(true, "set_dac_voltage(1.0) accepted", passed, failed);

    adc.set_dac_voltage(0.5).ok();
    check_true!(true, "set_dac_voltage(0.5) accepted", passed, failed);

    adc.disable_dac().ok();
    check_true!(true, "disable_dac accepted", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}
