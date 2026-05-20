use linux_embedded_hal::SpidevBus;
use periph::chips::led::Ws2812bFull;
use std::thread::sleep;
use std::time::{Duration, Instant};

const FRAME_MS: u64        = 33;
const RAINBOW_SECS: u64    = 10;
const STROBE_SECS: u64     = 2;
const STROBE_HALF_MS: u64  = 50;

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

fn main() {
    let spi_bus: u8    = std::env::var("SPI_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let spi_device: u8 = std::env::var("SPI_DEVICE").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let n_pixels: usize = std::env::var("N_PIXELS").ok().and_then(|v| v.parse().ok()).unwrap_or(30);

    let spi = SpidevBus::open(format!("/dev/spidev{}.{}", spi_bus, spi_device))
        .expect("open spidev");
    let mut strip = Ws2812bFull::new(spi, n_pixels);             // Create WS2812B full driver, (spi, n=n_pixels) → Self
    strip.set_brightness(180);                                   // Set global brightness, (value=0–255) → ()

    // --- Rainbow rotation: each pixel is assigned a hue offset by its position;
    //     the offset is advanced each frame so the rainbow rotates around the strip.
    //     Running at ~30 fps for 10 seconds gives a smooth continuous animation. ---
    let mut hue_offset: f32 = 0.0;
    let mut last_print = Instant::now();
    let rainbow_start = Instant::now();
    while rainbow_start.elapsed().as_secs() < RAINBOW_SECS {
        let frame_start = Instant::now();
        for i in 0..n_pixels {
            let h = (hue_offset + i as f32 / n_pixels as f32) % 1.0;
            let (r, g, b) = hsv_to_rgb(h, 1.0, 1.0);
            strip.set_pixel(i, r, g, b);                         // Set pixel i to rainbow hue, (index=0–n-1, r=0–255, g=0–255, b=0–255) → ()
        }
        strip.show().expect("show rainbow");                     // Transmit buffer to strip, () → Result<(), E>
        hue_offset = (hue_offset + 1.0 / (n_pixels as f32 * 2.0)) % 1.0;
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
        for i in 0..n_pixels {
            let h = (hue_offset + i as f32 / n_pixels as f32) % 1.0;
            let (r, g, b) = hsv_to_rgb(h, 1.0, 1.0);
            strip.set_pixel(i, r, g, b);                         // Set pixel i to rainbow hue, (index=0–n-1, r=0–255, g=0–255, b=0–255) → ()
        }
        strip.show().expect("show rainbow");                     // Transmit buffer to strip, () → Result<(), E>
        hue_offset = (hue_offset + 1.0 / (n_pixels as f32 * 2.0)) % 1.0;
        if last_print.elapsed().as_secs() >= 1 {
            println!("rainbow hue_offset={:.3}", hue_offset);
            last_print = Instant::now();
        }
        let elapsed = frame_start.elapsed().as_millis() as u64;
        if elapsed < FRAME_MS { sleep(Duration::from_millis(FRAME_MS - elapsed)); }
    }
}
