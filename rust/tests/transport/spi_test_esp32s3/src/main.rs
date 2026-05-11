#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::spi::master::{Spi, SpiConfig};
use esp_hal::gpio::GpioPin;
use esp_println::println;
use periph::transport::spi::SpiDevice;

esp_app_desc!();

const TEST_CS_PIN: i32 = 10;

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

    let spi = Spi::new(peripherals.SPI2, SpiConfig::default())
        .with_sck(peripherals.GPIO1)
        .with_mosi(peripherals.GPIO2)
        .with_miso(peripherals.GPIO3);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let cs = esp_hal::gpio::GpioPin::new(TEST_CS_PIN);

    let device = spi.device(cs);

    let tx_data = [0x01u8, 0x02, 0x03];
    device.write(&tx_data).expect("write");
    check_true!(true, "write completed", passed, failed);

    let mut rx_buf = [0u8; 3];
    device.read(&mut rx_buf).expect("read");
    check_true!(true, "read completed", passed, failed);

    let cmd = [0x55u8, 0xAA];
    let mut resp = [0u8; 2];
    device.transaction(&mut [
        embedded_hal::spi::Operation::Write(&cmd),
        embedded_hal::spi::Operation::Read(&mut resp),
    ]).expect("write_read");
    check_true!(true, "write_read completed", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}