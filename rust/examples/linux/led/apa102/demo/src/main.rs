//! APA102 demo example — 13-bit effective color depth demonstration.
use linux_embedded_hal::SpidevBus;
use spidev::{Spidev, SpidevOptions, SpiModeFlags};
use embedded_hal_bus::spi::ExclusiveDevice;

struct NullCs;
impl embedded_hal::digital::ErrorType for NullCs {
    type Error = core::convert::Infallible;
}
impl embedded_hal::digital::OutputPin for NullCs {
    fn set_low(&mut self) -> Result<(), Self::Error> { Ok(()) }
    fn set_high(&mut self) -> Result<(), Self::Error> { Ok(()) }
}
use periph::chips::led::Apa102Full;
use std::thread::sleep;
use std::time::{Duration, Instant};

fn hsv_to_rgb(h: f32, s: f32, v: f32) -> (u8, u8, u8) {
    if s == 0.0 {
        let c = (v * 255.0) as u8;
        return (c, c, c);
    }
    let i  = (h * 6.0) as i32;
    let f  = h * 6.0 - i as f32;
    let p  = (v * (1.0 - s) * 255.0) as u8;
    let q  = (v * (1.0 - s * f) * 255.0) as u8;
    let t  = (v * (1.0 - s * (1.0 - f)) * 255.0) as u8;
    let vv = (v * 255.0) as u8;
    match i % 6 {
        0 => (vv, t,  p),
        1 => (q,  vv, p),
        2 => (p,  vv, t),
        3 => (p,  q,  vv),
        4 => (t,  p,  vv),
        _ => (vv, p,  q),
    }
}

fn main() {
    let bus = std::env::var("SPI_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let device = std::env::var("SPI_DEVICE").ok().and_then(|v| v.parse().ok()).unwrap_or(0);

    let mut spidev = Spidev::open(format!("/dev/spidev{bus}.{device}")).unwrap();
    spidev.configure(
        &SpidevOptions::new()
            .max_speed_hz(1_000_000)
            .mode(SpiModeFlags::SPI_MODE_0)
            .build(),
    ).unwrap();
    let spi_bus = SpidevBus(spidev);
    let spi = ExclusiveDevice::new_no_delay(spi_bus, NullCs).unwrap();

    const N_PIXELS: usize = 30;
    const FRAME_MS: u64 = 16;      // ~60 fps
    const RAINBOW_MS: u64 = 10000;

    let mut strip = Apa102Full::new(spi, N_PIXELS);

    // --- 13-bit effective color depth demonstration ---
    // First pass: full hardware brightness (31) for maximum drive current
    // Second pass: hardware brightness 1 (1/31 current) to show hardware vs software dimming

    // --- Pass 1: Full hardware brightness (31) ---
    // Rainbow sweep at hardware brightness 31 uses full 8-bit PWM channels + 5-bit
    // hardware current control = 13-bit effective depth per channel.
    strip.set_brightness(255);                                          // Set global software brightness, (value=0–255) → ()
    let mut hue_offset = 0.0f32;
    let start = Instant::now();
    let mut last_print = start;
    while start.elapsed().as_millis() < RAINBOW_MS as u128 {
        for i in 0..N_PIXELS {
            let h = (hue_offset + i as f32 / N_PIXELS as f32) % 1.0;
            let (r, g, b) = hsv_to_rgb(h, 1.0, 1.0);
            strip.set_pixel(i, r, g, b, 31);                            // Set pixel i to rainbow hue at hw brightness 31, (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → ()
        }
        strip.show().unwrap();                                          // Transmit buffer to strip, () → ()
                                                                      // applies software brightness scaling then calls connection.write()
        hue_offset = (hue_offset + 1.0 / (N_PIXELS as f32 * 2.0)) % 1.0;
        let now = Instant::now();
        if now.duration_since(last_print).as_secs() >= 1 {
            println!("rainbow hw_brightness=31 hue_offset={:.3}", hue_offset);
            last_print = now;
        }
        let elapsed = now.elapsed().as_millis() as u64;
        if elapsed < FRAME_MS {
            sleep(Duration::from_millis(FRAME_MS - elapsed));
        }
    }

    // --- Pass 2: Low hardware brightness (1) ---
    // Same 8-bit RGB values but hardware brightness=1 (1/31 drive current).
    // Demonstrates hardware current control vs software brightness scaling.
    strip.set_brightness(255);                                          // Set global software brightness, (value=0–255) → ()
    hue_offset = 0.0f32;
    let start = Instant::now();
    let mut last_print = start;
    while start.elapsed().as_millis() < RAINBOW_MS as u128 {
        for i in 0..N_PIXELS {
            let h = (hue_offset + i as f32 / N_PIXELS as f32) % 1.0;
            let (r, g, b) = hsv_to_rgb(h, 1.0, 1.0);
            strip.set_pixel(i, r, g, b, 1);                             // Set pixel i to rainbow hue at hw brightness 1, (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → ()
        }
        strip.show().unwrap();                                          // Transmit buffer to strip, () → ()
                                                                      // applies software brightness scaling then calls connection.write()
        hue_offset = (hue_offset + 1.0 / (N_PIXELS as f32 * 2.0)) % 1.0;
        let now = Instant::now();
        if now.duration_since(last_print).as_secs() >= 1 {
            println!("rainbow hw_brightness=1 hue_offset={:.3}", hue_offset);
            last_print = now;
        }
        let elapsed = now.elapsed().as_millis() as u64;
        if elapsed < FRAME_MS {
            sleep(Duration::from_millis(FRAME_MS - elapsed));
        }
    }

    strip.off().unwrap();                                               // Turn off all pixels, () → ()
    sleep(Duration::from_secs(1));
}