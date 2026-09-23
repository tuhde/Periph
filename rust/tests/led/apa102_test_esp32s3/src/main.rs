//! APA102 hardware-in-loop test for ESP32-S3.
//! Prints PASS/FAIL per check and a final ===DONE=== line.
#![no_std]
#![no_main]

use core::convert::Infallible;

use embedded_hal::digital::{ErrorType, OutputPin};
use embedded_hal_bus::spi::ExclusiveDevice;
use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::main;
use esp_hal::spi::master::{Config, Spi};
use esp_hal::spi::Mode;
use esp_hal::time::Rate;
use esp_println::println;
use periph::chips::led::{Apa102Full, Apa102Minimal};

esp_app_desc!();

/// APA102 has no chip-select line; the driver still wants an `SpiDevice`,
/// so wrap the bus with a no-op CS.
struct NoCs;

impl ErrorType for NoCs {
    type Error = Infallible;
}

impl OutputPin for NoCs {
    fn set_low(&mut self) -> Result<(), Infallible> { Ok(()) }
    fn set_high(&mut self) -> Result<(), Infallible> { Ok(()) }
}

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

/// APA102 is clocked SPI: SCK (GPIO36) -> CI, MOSI (GPIO35) -> DI, 1 MHz mode 0.
fn spi_config() -> Config {
    Config::default()
        .with_frequency(Rate::from_mhz(1))
        .with_mode(Mode::_0)
}

#[main]
fn main() -> ! {
    let mut peripherals = esp_hal::init(esp_hal::Config::default());
    let delay = Delay::new();

    let mut passed = 0i32;
    let mut failed = 0i32;

    // --- Apa102Minimal ---
    let spi = Spi::new(peripherals.SPI2.reborrow(), spi_config())
        .unwrap()
        .with_sck(peripherals.GPIO36.reborrow())
        .with_mosi(peripherals.GPIO35.reborrow());
    let spi = ExclusiveDevice::new_no_delay(spi, NoCs).unwrap();
    let mut strip = Apa102Minimal::new(spi, 8);
    check_true!(strip.fill(255, 0, 0).is_ok(), "fill_red_accepted", passed, failed);
    delay.delay_millis(100);
    check_true!(strip.fill(0, 255, 0).is_ok(), "fill_green_accepted", passed, failed);
    delay.delay_millis(100);
    check_true!(strip.fill(0, 0, 255).is_ok(), "fill_blue_accepted", passed, failed);
    delay.delay_millis(100);
    check_true!(strip.off().is_ok(), "off_accepted", passed, failed);
    drop(strip);

    // --- Apa102Full ---
    let spi = Spi::new(peripherals.SPI2.reborrow(), spi_config())
        .unwrap()
        .with_sck(peripherals.GPIO36.reborrow())
        .with_mosi(peripherals.GPIO35.reborrow());
    let spi = ExclusiveDevice::new_no_delay(spi, NoCs).unwrap();
    let mut strip = Apa102Full::new(spi, 8);
    check_true!(strip.get_brightness() == 255, "default_brightness_255", passed, failed);

    strip.set_pixel(0, 255, 0, 0, 31);
    check_true!(strip.show().is_ok(), "set_pixel_show_accepted", passed, failed);
    delay.delay_millis(100);

    strip.set_pixels(&[&[255, 0, 0], &[0, 255, 0], &[0, 0, 255]]);
    check_true!(strip.show().is_ok(), "set_pixels_show_accepted", passed, failed);
    delay.delay_millis(100);

    // Per-pixel hardware brightness (fourth element, 0-31).
    strip.set_pixels(&[
        &[255, 0, 0, 31], &[255, 0, 0, 16], &[255, 0, 0, 8], &[255, 0, 0, 4],
        &[0, 255, 0, 31], &[0, 255, 0, 16], &[0, 255, 0, 8], &[0, 255, 0, 4],
    ]);
    check_true!(strip.show().is_ok(), "set_pixels_hw_brightness_show_accepted", passed, failed);
    delay.delay_millis(100);

    strip.set_brightness(128);
    check_true!(strip.get_brightness() == 128, "brightness_setter", passed, failed);
    check_true!(strip.show().is_ok(), "show_brightness128_accepted", passed, failed);
    delay.delay_millis(100);

    strip.set_brightness(255);
    strip.rotate(1);
    check_true!(strip.show().is_ok(), "rotate_show_accepted", passed, failed);
    delay.delay_millis(100);

    check_true!(strip.fill_hsv(0.0, 1.0, 1.0).is_ok(), "fill_hsv_red_accepted", passed, failed);
    delay.delay_millis(100);
    check_true!(strip.fill_hsv(0.333, 1.0, 1.0).is_ok(), "fill_hsv_green_accepted", passed, failed);
    delay.delay_millis(100);
    check_true!(strip.fill_hsv(0.667, 1.0, 1.0).is_ok(), "fill_hsv_blue_accepted", passed, failed);
    delay.delay_millis(100);
    check_true!(strip.off().is_ok(), "full_off_accepted", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {}
}
