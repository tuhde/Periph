#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::display::{
    Pcf8576Full, BACKPLANES_4, BIAS_1_3, BANK_0, BLINK_2_HZ, BLINK_OFF,
};

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

    let init_ok = chip.clear().is_ok();
    check_true!(init_ok, "clear", passed, failed);

    let digits = [1u8, 2, 3, 4];
    let mut write_ok = true;
    for (i, d) in digits.iter().enumerate() {
        if chip.set_digit_7seg(i as u8, Pcf8576Full::SEVEN_SEG[*d as usize]).is_err() {
            write_ok = false;
            break;
        }
    }
    check_true!(write_ok, "set_digit_7seg", passed, failed);

    let raw_ok = chip.write_raw(0, &[0xED, 0x60, 0xA7, 0xE3]).is_ok();
    check_true!(raw_ok, "write_raw", passed, failed);

    let mode_ok = chip.set_mode(BACKPLANES_4, BIAS_1_3).is_ok();
    check_true!(mode_ok, "set_mode", passed, failed);

    let blink_ok = chip.set_blink(BLINK_2_HZ, false).is_ok()
        && chip.set_blink(BLINK_OFF, true).is_ok();
    check_true!(blink_ok, "set_blink", passed, failed);

    let bank_ok = chip.set_bank(BANK_0, BANK_0).is_ok();
    check_true!(bank_ok, "set_bank", passed, failed);

    let sel_ok = chip.device_select(0).is_ok();
    check_true!(sel_ok, "device_select", passed, failed);

    let _ = BACKPLANES_4;
    let _ = BIAS_1_3;
    let _ = BLINK_2_HZ;
    let _ = BLINK_OFF;
    let _ = BANK_0;

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}
