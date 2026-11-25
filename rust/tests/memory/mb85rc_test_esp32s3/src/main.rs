#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::memory::Mb85rcFull;

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

    let _delay = Delay::new();
    let mut passed = 0i32;
    let mut failed = 0i32;

    let mut chip = Mb85rcFull::new(i2c, TEST_ADDR);

    check_eq!("size", chip.size(), 32768usize, passed, failed);

    if chip.write_byte(0x0010, 0x5A).is_err() {
        println!("FAIL write_byte: bus error");
        failed += 1;
    } else {
        check_eq!("write_byte_read_byte_round_trip", chip.read_byte(0x0010).unwrap_or(0xFF), 0x5Au8, passed, failed);
    }

    let mut data = [0u8; 100];
    for (i, d) in data.iter_mut().enumerate() {
        *d = i as u8;
    }
    let _ = chip.write(0x0100, &data);
    let mut back = [0u8; 100];
    let _ = chip.read(0x0100, &mut back);
    check_eq!("chunked_write_read_round_trip", back, data, passed, failed);

    let _ = chip.fill(0x0100, 100, 0xC3);
    let _ = chip.read(0x0100, &mut back);
    check_eq!("fill", back, [0xC3u8; 100], passed, failed);

    let _ = chip.write(0x7FFE, &[0x12, 0x34]);
    let mut tail = [0u8; 2];
    let _ = chip.read(0x7FFE, &mut tail);
    check_eq!("last_bytes_round_trip", tail, [0x12u8, 0x34], passed, failed);

    let mut wrap = [0u8; 2];
    check_true!(chip.read(0x7FFF, &mut wrap).is_err(), "wrap_rejected", passed, failed);

    match chip.read_device_id() {
        Ok(id) => check_eq!("read_device_id", id, (0x00Au16, 0x5u8, 0x10u8), passed, failed),
        Err(_) => println!("SKIP read_device_id (0x7C not accessible)"),
    }

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}
