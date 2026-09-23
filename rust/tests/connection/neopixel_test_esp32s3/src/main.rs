#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::main;
use esp_hal::spi::master::{Config, Spi};
use esp_hal::spi::Mode;
use esp_hal::time::Rate;
use esp_println::println;
use periph::connection::neopixel::NeoPixelConnection;

esp_app_desc!();

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

/// NeoPixel timing: SPI at 2.4 MHz, mode 0; MOSI (GPIO35) drives DIN.
fn spi_config() -> Config {
    Config::default()
        .with_frequency(Rate::from_khz(2400))
        .with_mode(Mode::_0)
}

#[main]
fn main() -> ! {
    let mut peripherals = esp_hal::init(esp_hal::Config::default());

    let mut passed = 0i32;
    let mut failed = 0i32;

    let spi = Spi::new(peripherals.SPI2.reborrow(), spi_config())
        .unwrap()
        .with_sck(peripherals.GPIO36.reborrow())
        .with_mosi(peripherals.GPIO35.reborrow());
    let mut connection = NeoPixelConnection::new(spi);
    // One GRB pixel, full red; the connection expands each bit to an SPI symbol.
    check_true!(connection.write(&[0x00, 0xFF, 0x00]).is_ok(), "write_accepted_data", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {}
}
