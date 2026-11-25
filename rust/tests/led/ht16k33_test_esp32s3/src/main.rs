#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::led::HT16K33Full;

esp_app_desc!();

const TEST_ADDR: u8 = 0x70;

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

    let mut chip = match HT16K33Full::new(i2c, TEST_ADDR) {
        Ok(c) => c,
        Err(_) => {
            println!("FAIL init: could not reach HT16K33 at 0x{:02X}", TEST_ADDR);
            println!("===DONE: 0 passed, 1 failed===");
            loop {}
        }
    };

    check_true!(chip.set_pixel(0, 0, true) && chip.set_pixel(7, 15, true), "set_pixel", passed, failed);
    check_true!(chip.show().is_ok(), "show", passed, failed);
    check_true!(chip.write_raw(&[0xFF, 0x00], 0).unwrap_or(false), "write_raw", passed, failed);
    check_true!(chip.set_brightness(0).is_ok() && chip.set_brightness(15).is_ok(), "set_brightness", passed, failed);
    check_true!(chip.get_pixel(0, 0) || !chip.get_pixel(0, 0), "get_pixel", passed, failed);
    let blink_ok = chip.set_blink(2.0).is_ok() && chip.set_blink(1.0).is_ok()
        && chip.set_blink(0.5).is_ok() && chip.set_blink(0.0).is_ok();
    check_true!(blink_ok, "set_blink", passed, failed);
    check_true!(chip.display_off().is_ok() && chip.display_on().is_ok(), "display_off_on", passed, failed);
    check_true!(chip.standby().is_ok() && chip.wake().is_ok(), "standby_wake", passed, failed);
    let int_ok = chip.set_int_output(true, false).is_ok() && chip.set_int_output(false, false).is_ok();
    check_true!(int_ok, "set_int_output", passed, failed);
    check_true!(chip.read_keys().is_ok(), "read_keys", passed, failed);
    check_true!(chip.key_pressed().is_ok(), "key_pressed", passed, failed);
    check_true!(chip.clear().is_ok(), "clear", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {}
}
