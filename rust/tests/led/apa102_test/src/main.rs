//! APA102 hardware-in-loop test for Linux (spidev).
//! Prints PASS/FAIL and exits with 0 on success.

use linux_embedded_hal::SpidevBus;
use periph::connection::spi::SpiConnection;
use periph::chips::led::{Apa102Minimal, Apa102Full};
use std::env;
use std::process;

static mut PASSED: u32 = 0;
static mut FAILED: u32 = 0;

fn check_true(label: &str, condition: bool) {
    unsafe {
        if condition {
            println!("PASS {}", label);
            PASSED += 1;
        } else {
            println!("FAIL {}", label);
            FAILED += 1;
        }
    }
}

fn check_eq_u8(label: &str, got: u8, expected: u8) {
    unsafe {
        if got == expected {
            println!("PASS {}", label);
            PASSED += 1;
        } else {
            println!("FAIL {}: got {}, expected {}", label, got, expected);
            FAILED += 1;
        }
    }
}

fn main() {
    let bus = env::var("SPI_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let device = env::var("SPI_DEVICE").ok().and_then(|v| v.parse().ok()).unwrap_or(0);

    let mut spidev = linux_embedded_hal::Spidev::open(format!("/dev/spidev{bus}.{device}")).unwrap();
    spidev.configure(
        &linux_embedded_hal::SpidevOptions::new()
            .max_speed_hz(1_000_000)
            .mode(spidev::SpiModeFlags::SPI_MODE_0)
            .build(),
    ).unwrap();
    let spi_bus = SpidevBus(spidev);
    let spi = embedded_hal_bus::spi::ExclusiveDevice::new_no_delay(spi_bus, None).unwrap();

    // --- APA102Minimal ---
    {
        let mut strip = Apa102Minimal::new(spi, 8);

        strip.fill(255, 0, 0).unwrap();
        check_true("fill(255,0,0) accepted", true);

        strip.fill(0, 255, 0).unwrap();
        check_true("fill(0,255,0) accepted", true);

        strip.fill(0, 0, 255).unwrap();
        check_true("fill(0,0,255) accepted", true);

        strip.off().unwrap();
        check_true("off() accepted", true);
    }

    // Need new SPI connection for Full
    let mut spidev2 = linux_embedded_hal::Spidev::open(format!("/dev/spidev{bus}.{device}")).unwrap();
    spidev2.configure(
        &linux_embedded_hal::SpidevOptions::new()
            .max_speed_hz(1_000_000)
            .mode(spidev::SpiModeFlags::SPI_MODE_0)
            .build(),
    ).unwrap();
    let spi_bus2 = SpidevBus(spidev2);
    let spi2 = embedded_hal_bus::spi::ExclusiveDevice::new_no_delay(spi_bus2, None).unwrap();

    // --- APA102Full ---
    {
        let mut strip = Apa102Full::new(spi2, 8);

        check_eq_u8("default brightness is 255", strip.get_brightness(), 255);

        strip.set_pixel(0, 255, 0, 0, 31);
        strip.show().unwrap();
        check_true("set_pixel + show accepted", true);

        strip.set_pixels(&[&[255, 0, 0], &[0, 255, 0], &[0, 0, 255]]);
        strip.show().unwrap();
        check_true("set_pixels + show accepted", true);

        // set_pixels with per-pixel hardware brightness
        strip.set_pixels(&[
            &[255, 0, 0, 31], &[255, 0, 0, 16], &[255, 0, 0, 8], &[255, 0, 0, 4],
            &[0, 255, 0, 31], &[0, 255, 0, 16], &[0, 255, 0, 8], &[0, 255, 0, 4],
        ]);
        strip.show().unwrap();
        check_true("set_pixels with hardware brightness + show accepted", true);

        strip.set_brightness(128);
        check_eq_u8("brightness setter", strip.get_brightness(), 128);
        strip.show().unwrap();
        check_true("show() with brightness=128 accepted", true);

        strip.set_brightness(255);

        strip.rotate(1);
        strip.show().unwrap();
        check_true("rotate + show accepted", true);

        strip.fill_hsv(0.0, 1.0, 1.0).unwrap();
        check_true("fill_hsv(0.0) accepted", true);

        strip.fill_hsv(0.333, 1.0, 1.0).unwrap();
        check_true("fill_hsv(0.333) accepted", true);

        strip.fill_hsv(0.667, 1.0, 1.0).unwrap();
        check_true("fill_hsv(0.667) accepted", true);

        strip.off().unwrap();
        check_true("off() on Full accepted", true);
    }

    unsafe {
        println!("===DONE: {} passed, {} failed===", PASSED, FAILED);
        process::exit(if FAILED == 0 { 0 } else { 1 });
    }
}