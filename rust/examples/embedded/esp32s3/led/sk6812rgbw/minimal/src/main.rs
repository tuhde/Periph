#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::spi::master::{Config, Spi};
use esp_hal::spi::SpiMode;
use esp_println::println;
use periph::chips::led::Sk6812RgbwMinimal;

esp_app_desc!();

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let spi = Spi::new(peripherals.SPI2, Config::default()
        .with_frequency(2_400_000)
        .with_mode(SpiMode::Mode0))
        .unwrap()
        .with_mosi(peripherals.GPIO3);
    let mut delay = Delay::new();

    let mut strip = Sk6812RgbwMinimal::new(spi, 30);          // Create SK6812RGBW driver, (spi, n=pixel count) → Self

    loop {
        strip.fill(255, 0, 0, 0).expect("fill red");                // Fill all pixels with one colour, (r=0–255, g=0–255, b=0–255, w=0–255) → Result<(), E>
        delay.delay_ms(1000);
        strip.fill(0, 255, 0, 0).expect("fill green");              // Fill all pixels with one colour, (r=0–255, g=0–255, b=0–255, w=0–255) → Result<(), E>
        delay.delay_ms(1000);
        strip.fill(0, 0, 255, 0).expect("fill blue");               // Fill all pixels with one colour, (r=0–255, g=0–255, b=0–255, w=0–255) → Result<(), E>
        delay.delay_ms(1000);
        strip.fill(0, 0, 0, 255).expect("fill white");              // Fill all pixels with one colour, (r=0–255, g=0–255, b=0–255, w=0–255) → Result<(), E>
        delay.delay_ms(1000);
        strip.off().expect("off");                                   // Turn off all pixels, () → Result<(), E>
        delay.delay_ms(1000);
    }
}
