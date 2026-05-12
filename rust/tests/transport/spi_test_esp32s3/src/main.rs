#![no_std]
#![no_main]

use embedded_hal::spi::{Operation, SpiDevice};
use embedded_hal_bus::spi::ExclusiveDevice;
use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::{
    gpio::Output,
    spi::master::{Config, Spi},
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

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let spi_bus = Spi::new(peripherals.SPI2, Config::default())
        .unwrap()
        .with_mosi(peripherals.GPIO3)
        .with_miso(peripherals.GPIO4)
        .with_sck(peripherals.GPIO5);

    let cs = Output::new(peripherals.GPIO6, esp_hal::gpio::Level::High);
    let mut device = ExclusiveDevice::new_no_delay(spi_bus, cs).unwrap();

    let mut passed = 0i32;
    let mut failed = 0i32;

    check_true!(device.write(&[0x00]).is_ok(), "write_ok", passed, failed);

    let mut buf = [0u8; 1];
    check_true!(device.read(&mut buf).is_ok(), "read_ok", passed, failed);

    let mut buf = [0u8; 1];
    check_true!(
        device
            .transaction(&mut [Operation::Write(&[0x00]), Operation::Read(&mut buf)])
            .is_ok(),
        "write_read_ok",
        passed,
        failed
    );

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}
