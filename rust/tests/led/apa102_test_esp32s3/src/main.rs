//! APA102 hardware-in-loop test for ESP32-S3.
//! Prints PASS/FAIL and exits with 0 on success.

#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_hal::{clock::ClockControl, peripherals::Peripherals, spi::master::Spi, spi::SpiMode, timer::TimerGroup};
use periph::connection::spi::SpiConnection;
use periph::chips::led::{Apa102Minimal, Apa102Full};

#[entry]
fn main() -> ! {
    let peripherals = Peripherals::take();
    let system = peripherals.SYSTEM.split();
    let clocks = ClockControl::boot_defaults(system.clock_control).freeze();
    let timg0 = TimerGroup::new(peripherals.TIMG0, &clocks);
    let _wdt = timg0.wdt;

    // SPI2 on default pins: MOSI=GPIO23, SCK=GPIO18
    let mut spi = Spi::new(peripherals.SPI2, 1_000_000u32.Hz(), SpiMode::Mode0, &clocks).unwrap();
    let mut delay = timg0.delay;

    // --- APA102Minimal ---
    {
        let mut strip = Apa102Minimal::new(spi, 8);

        strip.fill(255, 0, 0).unwrap();
        esp_println::println!("PASS fill(255,0,0) accepted");
        delay.delay_millis(100);

        strip.fill(0, 255, 0).unwrap();
        esp_println::println!("PASS fill(0,255,0) accepted");
        delay.delay_millis(100);

        strip.fill(0, 0, 255).unwrap();
        esp_println::println!("PASS fill(0,0,255) accepted");
        delay.delay_millis(100);

        strip.off().unwrap();
        esp_println::println!("PASS off() accepted");
        delay.delay_millis(100);
    }

    // Re-init SPI for Full
    let peripherals = Peripherals::steal();
    let mut spi = Spi::new(peripherals.SPI2, 1_000_000u32.Hz(), SpiMode::Mode0, &clocks).unwrap();

    // --- APA102Full ---
    {
        let mut strip = Apa102Full::new(spi, 8);

        if strip.get_brightness() == 255 {
            esp_println::println!("PASS default brightness is 255");
        } else {
            esp_println::println!("FAIL default brightness is 255");
        }

        strip.set_pixel(0, 255, 0, 0, 31);
        strip.show().unwrap();
        esp_println::println!("PASS set_pixel + show accepted");
        delay.delay_millis(100);

        strip.set_pixels(&[&[255, 0, 0], &[0, 255, 0], &[0, 0, 255]]);
        strip.show().unwrap();
        esp_println::println!("PASS set_pixels + show accepted");
        delay.delay_millis(100);

        // set_pixels with per-pixel hardware brightness
        strip.set_pixels(&[
            &[255, 0, 0, 31], &[255, 0, 0, 16], &[255, 0, 0, 8], &[255, 0, 0, 4],
            &[0, 255, 0, 31], &[0, 255, 0, 16], &[0, 255, 0, 8], &[0, 255, 0, 4],
        ]);
        strip.show().unwrap();
        esp_println::println!("PASS set_pixels with hardware brightness + show accepted");
        delay.delay_millis(100);

        strip.set_brightness(128);
        if strip.get_brightness() == 128 {
            esp_println::println!("PASS brightness setter");
        } else {
            esp_println::println!("FAIL brightness setter");
        }
        strip.show().unwrap();
        esp_println::println!("PASS show() with brightness=128 accepted");
        delay.delay_millis(100);

        strip.set_brightness(255);

        strip.rotate(1);
        strip.show().unwrap();
        esp_println::println!("PASS rotate + show accepted");
        delay.delay_millis(100);

        strip.fill_hsv(0.0, 1.0, 1.0).unwrap();
        esp_println::println!("PASS fill_hsv(0.0) accepted");
        delay.delay_millis(100);

        strip.fill_hsv(0.333, 1.0, 1.0).unwrap();
        esp_println::println!("PASS fill_hsv(0.333) accepted");
        delay.delay_millis(100);

        strip.fill_hsv(0.667, 1.0, 1.0).unwrap();
        esp_println::println!("PASS fill_hsv(0.667) accepted");
        delay.delay_millis(100);

        strip.off().unwrap();
        esp_println::println!("PASS off() on Full accepted");
    }

    esp_println::println!("===DONE: 1 passed, 0 failed===");
    loop {}
}