//! TPIC6B595 hardware-in-loop test for ESP32-S3.
//! Wiring: SCK GPIO36 -> SRCK, MOSI GPIO35 -> SER IN, GPIO17 -> RCK;
//! SRCLR tied high and G tied low on the board (not driven here).
#![no_std]
#![no_main]

use embedded_hal::digital::OutputPin;
use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::gpio::{Level, Output, OutputConfig};
use esp_hal::main;
use esp_hal::spi::master::{Config, Spi};
use esp_hal::spi::Mode;
use esp_hal::time::Rate;
use esp_println::println;
use periph::chips::io_expander::{Tpic6b595Full, Tpic6b595Minimal};

esp_app_desc!();

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

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

    // --- Tpic6b595Minimal ---
    let spi = Spi::new(peripherals.SPI2.reborrow(), spi_config())
        .unwrap()
        .with_sck(peripherals.GPIO36.reborrow())
        .with_mosi(peripherals.GPIO35.reborrow());
    let rck = Output::new(peripherals.GPIO17.reborrow(), Level::Low, OutputConfig::default());
    let chip = Tpic6b595Minimal::new(spi, rck, None::<Output>, None::<Output>, 1);
    check_true!(chip.is_ok(), "construct_minimal", passed, failed);
    let chip = chip.expect("init TPIC6B595");

    let mut p0 = chip.pin(0);
    check_true!(p0.set_high().is_ok(), "pin0_set_high", passed, failed);
    delay.delay_millis(100);
    check_true!(p0.set_low().is_ok(), "pin0_set_low", passed, failed);
    check_true!(chip.write_port(0, 0xA5).is_ok(), "write_port_accepted", passed, failed);
    delay.delay_millis(100);
    check_true!(chip.fill(true).is_ok(), "fill_on_accepted", passed, failed);
    delay.delay_millis(100);
    check_true!(chip.off().is_ok(), "off_accepted", passed, failed);
    drop(chip);

    // --- Tpic6b595Full ---
    let spi = Spi::new(peripherals.SPI2.reborrow(), spi_config())
        .unwrap()
        .with_sck(peripherals.GPIO36.reborrow())
        .with_mosi(peripherals.GPIO35.reborrow());
    let rck = Output::new(peripherals.GPIO17.reborrow(), Level::Low, OutputConfig::default());
    let mut full = Tpic6b595Full::new(spi, rck, None::<Output>, None::<Output>, 1).expect("init TPIC6B595 Full");
    check_true!(full.write_all(&[0x0F]).is_ok(), "write_all_accepted", passed, failed);
    delay.delay_millis(100);
    // No SRCLR/G wired: both must be reported as unsupported rather than panic.
    check_true!(full.clear().is_err(), "clear_without_srclr_errors", passed, failed);
    check_true!(full.set_output_enable(true).is_err(), "output_enable_without_g_errors", passed, failed);
    check_true!(full.off().is_ok(), "full_off_accepted", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {}
}
