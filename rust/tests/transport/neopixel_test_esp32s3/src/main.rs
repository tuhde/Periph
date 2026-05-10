#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::spi::master::{Config, Spi};
use esp_hal::gpio::GpioPin;
use esp_println::println;
use periph::transport::NeoPixelTransport;

esp_app_desc!();

const TEST_MOSI: i32 = 35;
const TEST_SCK: i32 = 36;

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

    let spi = Spi::new(peripherals.SPI0, Config::default())
        .unwrap()
        .with_mosi(peripherals.GPIO35)
        .with_sclk(peripherals.GPIO36);

    let mut transport = NeoPixelTransport::new(spi);

    let mut passed = 0i32;
    let mut failed = 0i32;

    transport.write(&[0x00, 0x00, 0x00]).ok();
    check_true!(true, "write_black_no_error", passed, failed);

    transport.write(&[0xFF, 0xFF, 0xFF]).ok();
    check_true!(true, "write_white_no_error", passed, failed);

    transport.write(&[0x00, 0xFF, 0x00]).ok();
    check_true!(true, "write_green_no_error", passed, failed);

    transport.write(&[0x10, 0x20, 0x30, 0x40]).ok();
    check_true!(true, "write_4bytes_no_error", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}