#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::spi::master::{Config, Spi};
use esp_hal::spi::SpiMode;
use esp_println::println;
use periph::chips::led::Sk6812RgbwFull;

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

    let mut strip = Sk6812RgbwFull::new(spi, 30);             // Create SK6812RGBW full driver, (spi, n=30) → Self
    strip.set_brightness(180);                                      // Set global brightness, (value=0–255) → ()

    loop {
        // --- Rainbow rotation: each pixel is assigned a hue offset by its position;
        //     the offset advances each frame so the rainbow rotates around the strip.
        //     RGB channels only (w=0) for 10 seconds at ~30 fps. ---
        let mut hue_offset: f32 = 0.0;
        let mut last_print = Instant::now();
        let rainbow_start = Instant::now();
        while rainbow_start.elapsed().as_secs() < RAINBOW_SECS {
            let frame_start = Instant::now();
            for i in 0..30 {
                let h = (hue_offset + i as f32 / 30 as f32) % 1.0;
                let (r, g, b) = hsv_to_rgb(h, 1.0, 1.0);
                strip.set_pixel(i, r, g, b, 0);                    // Set pixel i to rainbow hue (w=0), (index=0–n-1, r=0–255, g=0–255, b=0–255, w=0–255) → ()
            }
            strip.show().expect("show rainbow");                   // Transmit buffer to strip, () → Result<(), E>
            hue_offset = (hue_offset + 1.0 / (30 as f32 * 2.0)) % 1.0;
            if last_print.elapsed().as_secs() >= 1 {
                println!("rainbow hue_offset={:.3}", hue_offset);
                last_print = Instant::now();
            }
            let elapsed = frame_start.elapsed().as_millis() as u64;
            if elapsed < FRAME_MS { sleep(Duration::from_millis(FRAME_MS - elapsed)); }
        }

        // --- Warm-white strobe: showcases the dedicated white element.
        //     All four channels active (r=255, g=200, b=150, w=255) gives a warm,
        //     high-CRI white; toggling at 5 Hz for 2 seconds draws the eye to the
        //     difference between mixed-RGB white and the native W element. ---
        strip.set_brightness(255);                                  // Set global brightness, (value=0–255) → ()
        strip.fill(255, 200, 150, 255).expect("fill warm white");   // Pre-load warm white (RGB+W) into buffer, (r=0–255, g=0–255, b=0–255, w=0–255) → Result<(), E>
        let warm_start = Instant::now();
        let mut state = true;
        while warm_start.elapsed().as_secs() < WARM_SECS {
            strip.set_brightness(if state { 255 } else { 0 });      // Set global brightness, (value=0–255) → ()
            strip.show().expect("show warm strobe");                // Transmit buffer to strip, () → Result<(), E>
            state = !state;
            sleep(Duration::from_millis(WARM_HALF_MS));
        }

        // --- Return to continuous rainbow ---
        strip.set_brightness(180);                                  // Set global brightness, (value=0–255) → ()
    }
}
