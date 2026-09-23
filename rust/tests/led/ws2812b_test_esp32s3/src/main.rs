#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::main;
use esp_hal::spi::master::{Config, Spi};
use esp_hal::spi::Mode;
use esp_hal::time::Rate;
use esp_println::println;
use periph::chips::led::{Ws2812bMinimal, Ws2812bFull};

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

    // --- Ws2812bMinimal smoke test ---
    let spi = Spi::new(peripherals.SPI2.reborrow(), spi_config())
        .unwrap()
        .with_sck(peripherals.GPIO36.reborrow())
        .with_mosi(peripherals.GPIO35.reborrow());
    let mut strip = Ws2812bMinimal::new(spi, 8);
    check_true!(strip.fill(255, 0, 0).is_ok(), "fill_red_accepted", passed, failed);
    check_true!(strip.off().is_ok(), "off_accepted", passed, failed);
    drop(strip);

    // --- Ws2812bFull smoke test ---
    let spi = Spi::new(peripherals.SPI2.reborrow(), spi_config())
        .unwrap()
        .with_sck(peripherals.GPIO36.reborrow())
        .with_mosi(peripherals.GPIO35.reborrow());
    let mut full = Ws2812bFull::new(spi, 8);
    full.set_pixel(0, 255, 0, 0);
    check_true!(full.show().is_ok(), "set_pixel_show_accepted", passed, failed);
    full.set_brightness(128);
    check_true!(full.show().is_ok(), "show_brightness128_accepted", passed, failed);
    check_true!(full.fill_hsv(0.0, 1.0, 1.0).is_ok(), "fill_hsv_accepted", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {}
}
