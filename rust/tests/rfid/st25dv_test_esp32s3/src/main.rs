//! ST25DV hardware-in-loop test for ESP32-S3.
//! Wiring: GPIO1 -> SDA, GPIO2 -> SCL; user area 0x53, system area 0x57.
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_hal::time::Rate;
use periph::chips::rfid::St25dvFull;

esp_app_desc!();

#[main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default().with_frequency(Rate::from_khz(400)))
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();
    let mut chip = St25dvFull::new(i2c, Delay::new(), 0x53, 0x57);

    esp_println::println!("ST25DV test started");

    let mut passed = 0i32;
    let mut failed = 0i32;

    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { esp_println::println!("PASS {}", $label); passed += 1; }
            else      { esp_println::println!("FAIL {}", $label); failed += 1; }
        };
    }

    check_true!(chip.init().is_ok(), "init");
    check_true!(chip.ic_reference() == 0x24 || chip.ic_reference() == 0x26, "ic_reference");
    let size = chip.memory_size();
    check_true!(size == 512 || size == 2048 || size == 8192, "memory_size");
    let uid = chip.uid().unwrap_or([0; 8]);
    check_true!(uid[0] == 0xE0 && uid[1] == 0x02, "uid manufacturer");
    check_true!(chip.ic_revision().is_ok(), "ic_revision");

    let addr = (size - 64) as u16;
    let mut saved = [0u8; 40];
    check_true!(chip.read(addr, &mut saved).is_ok(), "read ok");
    let mut pattern = [0u8; 40];
    for (i, b) in pattern.iter_mut().enumerate() {
        *b = (i as u8).wrapping_mul(7).wrapping_add(3);
    }
    check_true!(chip.write(addr, &pattern).is_ok(), "write ok");
    let mut back = [0u8; 40];
    check_true!(chip.read(addr, &mut back).is_ok() && back == pattern, "write/read round-trip");
    check_true!(chip.write(addr, &saved).is_ok(), "restore");
    let mut two = [0u8; 2];
    check_true!(chip.read((size - 1) as u16, &mut two).is_err(), "range rejected");

    check_true!(chip.present_password(&[0u8; 8]).unwrap_or(false), "present factory password");
    check_true!(chip.session_open().unwrap_or(false), "session open after present");
    check_true!(matches!(chip.get_area_protection(1), Ok((rf, i2c)) if rf <= 0x0F && i2c <= 3), "get_area_protection");
    check_true!(chip.get_gpo().is_ok(), "get_gpo");
    check_true!(chip.field_present().is_ok(), "field_present");
    check_true!(chip.poll_interrupt().is_ok(), "poll_interrupt");
    check_true!(chip.mailbox_status().is_ok(), "mailbox_status");
    let mut ndef = [0u8; 256];
    check_true!(chip.read_ndef(&mut ndef).is_ok(), "read_ndef");
    check_true!(chip.wait_write_complete().is_ok(), "wait_write_complete");

    esp_println::println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {
        delay.delay_ms(1000);
    }
}
