#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::memory::Eeprom24Aa02UidFull;

esp_app_desc!();

const TEST_ADDR: u8 = 0x50;

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

macro_rules! check_eq {
    ($label:expr, $got:expr, $expected:expr, $passed:expr, $failed:expr) => {
        if $got == $expected {
            println!("PASS {}", $label);
            $passed += 1;
        } else {
            println!("FAIL {}: got {:?}, expected {:?}", $label, $got, $expected);
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

    let mut delay = Delay::new();
    let mut passed = 0i32;
    let mut failed = 0i32;

    let mut chip = Eeprom24Aa02UidFull::new(i2c, TEST_ADDR);

    let uid = match chip.read_uid() {
        Ok(v) => v,
        Err(_) => [0; 4],
    };
    check_true!(uid.len() == 4, "read_uid_length", passed, failed);
    check_eq!("read_manufacturer_code", chip.read_manufacturer_code().unwrap_or(0xFF), 0x29u8, passed, failed);
    check_eq!("read_device_code",       chip.read_device_code().unwrap_or(0xFF),       0x41u8, passed, failed);

    if chip.write_byte(0x10, 0x5A, &mut delay).is_err() {
        println!("FAIL write_byte: bus error");
        failed += 1;
    } else {
        check_eq!("write_byte_read_byte_round_trip", chip.read_byte(0x10).unwrap_or(0xFF), 0x5Au8, passed, failed);
    }

    if chip.write_page(0x40, &[0x11, 0x22, 0x33, 0x44], &mut delay).is_err() {
        println!("FAIL write_page: bus error");
        failed += 1;
    } else {
        let mut page_read = [0u8; 4];
        let _ = chip.read(0x40, &mut page_read);
        check_eq!("write_page_read_back", page_read, [0x11u8, 0x22, 0x33, 0x44], passed, failed);
    }

    let _ = chip.write(0x06, &[0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF], &mut delay);
    let mut cross_read = [0u8; 6];
    let _ = chip.read(0x06, &mut cross_read);
    check_eq!("cross_page_write_read_back", cross_read, [0xAAu8, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF], passed, failed);

    let uid2 = chip.read_uid().unwrap_or([0; 4]);
    check_eq!("uid_unchanged_after_writes", uid2, uid, passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}
