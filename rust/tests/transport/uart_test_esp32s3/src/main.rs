#![no_std]
#![no_main]

use embedded_io::{Read, Write};
use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::{
    uart::{Config, Uart},
};
use esp_println::println;

esp_app_desc!();

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

// Assumes a loopback jumper bridging TX (GPIO17) and RX (GPIO18) on the ESP32-S3.
#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let mut uart = Uart::new(peripherals.UART1, Config::default())
        .unwrap()
        .with_tx(peripherals.GPIO17)
        .with_rx(peripherals.GPIO18);

    let mut passed = 0i32;
    let mut failed = 0i32;

    check_true!(uart.write_all(&[0x42]).is_ok(), "write_ok", passed, failed);

    let mut buf = [0u8; 1];
    check_true!(uart.read(&mut buf).is_ok(), "read_ok", passed, failed);
    check_true!(buf[0] == 0x42, "loopback_byte_matches", passed, failed);

    let mut resp = [0u8; 2];
    let _ = uart.write_all(&[0xA5, 0x5A]);
    let _ = uart.read(&mut resp);
    check_true!(
        resp[0] == 0xA5 && resp[1] == 0x5A,
        "write_read_loopback_matches",
        passed,
        failed
    );

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}
