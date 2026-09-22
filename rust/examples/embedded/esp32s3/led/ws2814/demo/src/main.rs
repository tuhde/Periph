#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::spi::master::{Config, Spi};
use esp_hal::spi::SpiMode;
use esp_println::println;
use periph::chips::led::Ws2814Full;

esp_app_desc!();

const N_PIXELS: usize   = 30;
const FRAME_MS: u32     = 33;    // ~30 fps
const RAINBOW_MS: u32   = 5000;
const FLASH_MS: u32     = 2000;
const DIM_MS: u32       = 2000;

fn hsv_to_rgb(h: f32, s: f32, v: f32) -> (u8, u8, u8) {
    if s == 0.0 { let c = (v * 255.0) as u8; return (c, c, c); }
    let i = (h * 6.0) as i32;
    let f = h * 6.0 - i as f32;
    let p = (v * (1.0 - s) * 255.0) as u8;
    let q = (v * (1.0 - s * f) * 255.0) as u8;
    let t = (v * (1.0 - s * (1.0 - f)) * 255.0) as u8;
    let vv = (v * 255.0) as u8;
    match i % 6 { 0 => (vv,t,p), 1 => (q,vv,p), 2 => (p,vv,t),
                  3 => (p,q,vv), 4 => (t,p,vv), _ => (vv,p,q) }
}

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let spi = Spi::new(peripherals.SPI2, Config::default()
        .with_frequency(2_400_000)
        .with_mode(SpiMode::Mode0))
        .unwrap()
        .with_mosi(peripherals.GPIO3);
    let mut delay = Delay::new();

    let mut strip = Ws2814Full::new(spi, N_PIXELS);                 // Create WS2814 full driver, (spi, n=N_PIXELS) → Self

    loop {
        // --- Rainbow rotation using RGB channels (white=0). Each pixel is
        //     assigned a hue offset by its position; the offset advances
        //     each frame so the rainbow rotates continuously around the
        //     strip. Demonstrates WS2814's identity RGBW wire order (no
        //     reorder), unlike SK6812RGBW's GRBW order. Runs at ~30 fps
        //     for 5 seconds (tracked by frame count, not wall clock, since
        //     no_std has no Instant). ---
        let mut hue_offset: f32 = 0.0;
        let mut elapsed_ms: u32 = 0;
        let mut since_print: u32 = 0;
        while elapsed_ms < RAINBOW_MS {
            for i in 0..N_PIXELS {
                let h = (hue_offset + i as f32 / N_PIXELS as f32) % 1.0;
                let (r, g, b) = hsv_to_rgb(h, 1.0, 1.0);
                strip.set_pixel(i, r, g, b, 0);                    // Set pixel i to rainbow hue (w=0), (index=0–n-1, r=0–255, g=0–255, b=0–255, w=0–255) → ()
            }
            strip.show().expect("show rainbow");                   // Transmit buffer to strip, () → Result<(), E>
            hue_offset = (hue_offset + 1.0 / (N_PIXELS as f32 * 2.0)) % 1.0;
            since_print += FRAME_MS;
            if since_print >= 1000 {
                println!("mode=rainbow brightness={}", strip.get_brightness());
                since_print = 0;
            }
            delay.delay_ms(FRAME_MS);
            elapsed_ms += FRAME_MS;
        }

        // --- Warm white at full brightness for 2 seconds. r=255, g=200,
        //     b=150, w=255 blends the dedicated white element with
        //     amber-tinted RGB, exercising the white channel and the 32-bit
        //     RGBW pixel word at full brightness. ---
        strip.fill(255, 200, 150, 255).expect("fill warm white");   // Fill all pixels warm white, (r=0–255, g=0–255, b=0–255, w=0–255) → Result<(), E>
        let mut flash_ms: u32 = 0;
        while flash_ms < FLASH_MS {
            println!("mode=warm-white brightness={}", strip.get_brightness());
            delay.delay_ms(100);
            flash_ms += 100;
        }

        // --- Dim warm white to 50% for 2 seconds. Demonstrates that
        //     brightness scaling is non-destructive: the stored RGBW
        //     values are unchanged, only the scale factor applied at
        //     show() time changes. ---
        strip.set_brightness(128);                                  // Set global brightness, (value=0–255) → ()
        strip.show().expect("show dimmed");                         // Transmit buffer to strip, () → Result<(), E>
        let mut dim_ms: u32 = 0;
        while dim_ms < DIM_MS {
            println!("mode=warm-white-dimmed brightness={}", strip.get_brightness());
            delay.delay_ms(100);
            dim_ms += 100;
        }

        // --- Cycle to cool white at full brightness. r=200, g=210, b=255,
        //     w=255 shifts the blend toward blue, showcasing the dedicated
        //     white element paired with a cool-tinted RGB base. ---
        strip.set_brightness(255);                                  // Set global brightness, (value=0–255) → ()
        strip.fill(200, 210, 255, 255).expect("fill cool white");   // Fill all pixels cool white, (r=0–255, g=0–255, b=0–255, w=0–255) → Result<(), E>
        println!("mode=cool-white brightness={}", strip.get_brightness());
        delay.delay_ms(1000);
    }
}
