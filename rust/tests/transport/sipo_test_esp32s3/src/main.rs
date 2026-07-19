#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::gpio::{Level, Output};
use esp_hal::spi::master::{Config, Spi};
use esp_println::println;
use periph::transport::sipo::SiPoTransport;

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

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    // Hardware SPI: SER IN on GPIO3 (MOSI), SRCK on GPIO5 (SCK).
    let spi_bus = Spi::new(peripherals.SPI2, Config::default())
        .unwrap()
        .with_mosi(peripherals.GPIO3)
        .with_sck(peripherals.GPIO5);

    // RCK on GPIO6, SRCLR on GPIO7, G on GPIO8.
    let rck = Output::new(peripherals.GPIO6, Level::Low);
    let srclr = Output::new(peripherals.GPIO7, Level::High);
    let g = Output::new(peripherals.GPIO8, Level::Low);

    let mut transport = SiPoTransport::new(spi_bus, rck, Some(srclr), Some(g)).unwrap();

    let mut passed = 0i32;
    let mut failed = 0i32;

    check_true!(transport.write(&[0xA5]).is_ok(), "write accepted", passed, failed);
    check_true!(
        transport.write(&[0x00, 0xFF]).is_ok(),
        "write multi-byte accepted",
        passed,
        failed
    );
    check_true!(transport.clear().is_ok(), "clear accepted", passed, failed);
    check_true!(
        transport.set_output_enable(false).is_ok(),
        "set_output_enable(false) accepted",
        passed,
        failed
    );
    check_true!(
        transport.set_output_enable(true).is_ok(),
        "set_output_enable(true) accepted",
        passed,
        failed
    );

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}
