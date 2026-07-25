#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::spi::master::{Config, Spi};
use esp_hal::spi::SpiMode;
use esp_println::println;
use periph::chips::led::Ws2812bFull;

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

    let mut strip = Ws2812bFull::new(spi, 30);             // Create WS2812B full driver, (spi, n=30) → Self
    strip.set_brightness(180);                                   // Set global brightness, (value=0–255) → ()

    // --- Rainbow rotation: each pixel is assigned a hue offset by its position;
    //     the offset is advanced each frame so the rainbow rotates around the strip.
    //     Running at ~30 fps for 10 seconds gives a smooth continuous animation. ---
    let mut hue_offset: f32 = 0.0;
    let mut last_print = Instant::now();
    let rainbow_start = Instant::now();
    while rainbow_start.elapsed().as_secs() < RAINBOW_SECS {
        let frame_start = Instant::now();
        for i in 0..30 {
            let h = (hue_offset + i as f32 / 30 as f32) % 1.0;
            let (r, g, b) = hsv_to_rgb(h, 1.0, 1.0);
            strip.set_pixel(i, r, g, b);                         // Set pixel i to rainbow hue, (index=0–n-1, r=0–255, g=0–255, b=0–255) → ()
        }
        strip.show().expect("show rainbow");                     // Transmit buffer to strip, () → Result<(), E>
        hue_offset = (hue_offset + 1.0 / (30 as f32 * 2.0)) % 1.0;
        if last_print.elapsed().as_secs() >= 1 {
            println!("rainbow hue_offset={:.3}", hue_offset);
            last_print = Instant::now();
        }
        let elapsed = frame_start.elapsed().as_millis() as u64;
        if elapsed < FRAME_MS { sleep(Duration::from_millis(FRAME_MS - elapsed)); }
    }

    // --- Strobe effect: alternate full white and off at 10 Hz for 2 seconds.
    //     Uses brightness=255 for maximum intensity then brightness=0 for off,
    //     demonstrating non-destructive brightness scaling — pixel values in the
    //     buffer are never zeroed. ---
    strip.set_brightness(255);                                   // Set global brightness, (value=0–255) → ()
    strip.fill(255, 255, 255).expect("fill white");              // Pre-load white into buffer, (r=0–255, g=0–255, b=0–255) → Result<(), E>
    let strobe_start = Instant::now();
    let mut state = true;
    while strobe_start.elapsed().as_secs() < STROBE_SECS {
        strip.set_brightness(if state { 255 } else { 0 });       // Set global brightness, (value=0–255) → ()
        strip.show().expect("show strobe");                      // Transmit buffer to strip, () → Result<(), E>
        state = !state;
        sleep(Duration::from_millis(STROBE_HALF_MS));
    }

    // --- Return to continuous rainbow ---
    strip.set_brightness(180);                                   // Set global brightness, (value=0–255) → ()
    hue_offset = 0.0;
    last_print = Instant::now();
    loop {
        let frame_start = Instant::now();
        for i in 0..30 {
            let h = (hue_offset + i as f32 / 30 as f32) % 1.0;
            let (r, g, b) = hsv_to_rgb(h, 1.0, 1.0);
            strip.set_pixel(i, r, g, b);                         // Set pixel i to rainbow hue, (index=0–n-1, r=0–255, g=0–255, b=0–255) → ()
        }
        strip.show().expect("show rainbow");                     // Transmit buffer to strip, () → Result<(), E>
        hue_offset = (hue_offset + 1.0 / (30 as f32 * 2.0)) % 1.0;
        if last_print.elapsed().as_secs() >= 1 {
            println!("rainbow hue_offset={:.3}", hue_offset);
            last_print = Instant::now();
        }
        let elapsed = frame_start.elapsed().as_millis() as u64;
        if elapsed < FRAME_MS { sleep(Duration::from_millis(FRAME_MS - elapsed)); }
    }
}
