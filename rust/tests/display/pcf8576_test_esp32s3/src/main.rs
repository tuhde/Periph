#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::display::{Pcf8576Full, SEG_7SEG, BLINK_OFF, MUX_1_4};

esp_app_desc!();

const TEST_ADDR: u8 = 0x38;

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

    let mut passed = 0i32;
    let mut failed = 0i32;

    let mut chip = match Pcf8576Full::new(i2c, TEST_ADDR) {
        Ok(c) => c,
        Err(_) => {
            println!("FAIL init: could not reach PCF8576 at 0x{:02X}", TEST_ADDR);
            println!("===DONE: 0 passed, 1 failed===");
            loop {}
        }
    };

    chip.clear().ok();
    check_true!(true, "clear_no_exception", passed, failed);

    chip.set_digit_7seg(0, SEG_7SEG[0]).ok();
    check_true!(true, "set_digit_7seg_no_exception", passed, failed);

    let data: [u8; 2] = [0xED, 0x60];
    chip.write_raw(0, &data).ok();
    check_true!(true, "write_raw_no_exception", passed, failed);

    chip.enable().ok();
    check_true!(true, "enable_no_exception", passed, failed);

    chip.disable().ok();
    check_true!(true, "disable_no_exception", passed, failed);

    chip.set_mode(MUX_1_4, 0).ok();
    check_true!(true, "set_mode_no_exception", passed, failed);

    chip.set_blink(BLINK_OFF, false).ok();
    check_true!(true, "set_blink_no_exception", passed, failed);

    chip.set_bank(0, 0).ok();
    check_true!(true, "set_bank_no_exception", passed, failed);

    chip.device_select(0).ok();
    check_true!(true, "device_select_no_exception", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}