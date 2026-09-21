#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::gpio::{Input, InputConfig, Level, Output, OutputConfig};
use esp_hal::spi::master::{Config as SpiConfig, Spi};
use esp_hal::spi::Mode as SpiMode;
use esp_hal::time::Rate;
use esp_println::println;
use periph::chips::io_expander::Tpic6b595Minimal;
use embedded_hal::digital::OutputPin;

esp_app_desc!();

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());
    let mut delay = Delay::new();

    let spi_cfg = SpiConfig::default()
        .with_frequency(Rate::from_mhz(1))
        .with_mode(SpiMode::Mode0);
    let spi = Spi::new(peripherals.SPI2, spi_cfg)
        .unwrap()
        .with_sck(peripherals.GPIO18)
        .with_mosi(peripherals.GPIO23);
    let rck = Output::new(peripherals.GPIO17, Level::Low, OutputConfig::default());

    let chip = Tpic6b595Minimal::new(spi, rck, None, None, 1).expect("init TPIC6B595");   // Create TPIC6B595 driver, (spi, rck, srclr=None, g=None, num_devices=1) → Result

    let mut p0 = chip.pin(0);                                                                  // Get pin proxy, (n=0) → ExPin
    let mut p7 = chip.pin(7);                                                                  // Get pin proxy, (n=7) → ExPin

    loop {
        p0.set_high().expect("set_high");                                                       // Set DMOS output ON, () → Result<(), E>
        p7.set_low().expect("set_low");                                                         // Set DMOS output OFF, () → Result<(), E>
        delay.delay_ms(500);
        p0.set_low().expect("set_low");                                                         // Set DMOS output OFF, () → Result<(), E>
        p7.set_high().expect("set_high");                                                       // Set DMOS output ON, () → Result<(), E>
        delay.delay_ms(500);
    }
}
