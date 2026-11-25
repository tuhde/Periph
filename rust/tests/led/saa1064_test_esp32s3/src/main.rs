//! SAA1064 hardware-in-loop test for ESP32-S3.
//! Wiring: GPIO1 -> SDA, GPIO2 -> SCL; address 0x38.
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_hal::time::Rate;
use periph::chips::led::SAA1064Full;

esp_app_desc!();

#[main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default().with_frequency(Rate::from_khz(100)))
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();
    macro_rules! sleep_ms { ($n:expr) => { delay.delay_ms($n) }; }
    let mut chip = SAA1064Full::new(i2c, 0x38).expect("new");

    esp_println::println!("SAA1064 test started");

    let mut passed = 0i32;
    let mut failed = 0i32;

    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { esp_println::println!("PASS {}", $label); passed += 1; }
            else      { esp_println::println!("FAIL {}", $label); failed += 1; }
        };
    }

    check_true!(chip.show_digits([Some(1), Some(2), Some(3), Some(4)]).is_ok(), "show_digits");
    check_true!(chip.show_number(1234).is_ok(), "show_number");
    check_true!(chip.write_raw(&[0x7F, 0x7F], 3).is_ok(), "write_raw");
    check_true!(chip.clear().is_ok(), "clear");
    check_true!(chip.set_mode(false).is_ok() && chip.set_mode(true).is_ok(), "set_mode");
    check_true!(chip.blank(true, false).is_ok() && chip.blank(false, true).is_ok() && chip.blank(false, false).is_ok(), "blank");
    check_true!(chip.set_current(3).is_ok() && chip.set_current(21).is_ok() && chip.set_current(12).is_ok(), "set_current");
    check_true!(chip.segment_test(true).is_ok() && chip.segment_test(false).is_ok(), "segment_test");
    check_true!(chip.set_segment_map([0, 1, 2, 3, 4, 5, 6, 7]), "set_segment_map");
    check_true!(chip.show_digit(1, Some(5), true).is_ok(), "show_digit");
    check_true!(chip.set_decimal_point(1, false).is_ok() && chip.set_decimal_point(4, true).is_ok(), "set_decimal_point");
    check_true!(chip.read_status().is_ok(), "read_status");
    check_true!(matches!(chip.power_reset_flag(), Ok(false)), "power_reset_flag_cleared");
    check_true!(chip.write_control(0x47).is_ok(), "write_control");
    let _ = chip.clear();

    esp_println::println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {
        sleep_ms!(1000);
    }
}
