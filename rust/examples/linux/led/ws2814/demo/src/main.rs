use linux_embedded_hal::SpidevBus;
use periph::chips::led::Ws2814Full;
use std::thread::sleep;
use std::time::{Duration, Instant};

const FRAME_MS: u64      = 33;     // ~30 fps
const RAINBOW_SECS: u64  = 5;
const FLASH_SECS: u64    = 2;
const DIM_SECS: u64      = 2;

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
    let spi_bus: u8     = std::env::var("SPI_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let spi_device: u8  = std::env::var("SPI_DEVICE").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let n_pixels: usize = std::env::var("N_PIXELS").ok().and_then(|v| v.parse().ok()).unwrap_or(30);

    let spi = SpidevBus::open(format!("/dev/spidev{}.{}", spi_bus, spi_device))
        .expect("open spidev");
    let mut strip = Ws2814Full::new(spi, n_pixels);                  // Create WS2814 full driver, (spi, n=n_pixels) → Self

    // --- Rainbow rotation using RGB channels (white=0). Each pixel is
    //     assigned a hue offset by its position; the offset advances each
    //     frame so the rainbow rotates continuously around the strip.
    //     Demonstrates that WS2814's RGBW wire order is identity (R, G, B, W
    //     with no reorder), unlike the SK6812RGBW's GRBW order. Runs at
    //     ~30 fps for 5 seconds. ---
    let mut hue_offset: f32 = 0.0;
    let mut last_print = Instant::now();
    let rainbow_start = Instant::now();
    while rainbow_start.elapsed().as_secs() < RAINBOW_SECS {
        let frame_start = Instant::now();
        for i in 0..n_pixels {
            let h = (hue_offset + i as f32 / n_pixels as f32) % 1.0;
            let (r, g, b) = hsv_to_rgb(h, 1.0, 1.0);
            strip.set_pixel(i, r, g, b, 0);                        // Set pixel i to rainbow hue (w=0), (index=0–n-1, r=0–255, g=0–255, b=0–255, w=0–255) → ()
        }
        strip.show().expect("show rainbow");                       // Transmit buffer to strip, () → Result<(), E>
        hue_offset = (hue_offset + 1.0 / (n_pixels as f32 * 2.0)) % 1.0;
        if last_print.elapsed().as_secs() >= 1 {
            println!("mode=rainbow brightness={}", strip.get_brightness());
            last_print = Instant::now();
        }
        let elapsed = frame_start.elapsed().as_millis() as u64;
        if elapsed < FRAME_MS { sleep(Duration::from_millis(FRAME_MS - elapsed)); }
    }

    // --- Warm white at full brightness for 2 seconds. r=255, g=200, b=150,
    //     w=255 blends the dedicated white element with amber-tinted RGB,
    //     exercising the white channel and the 32-bit RGBW pixel word at
    //     full brightness. ---
    strip.fill(255, 200, 150, 255).expect("fill warm white");       // Fill all pixels warm white, (r=0–255, g=0–255, b=0–255, w=0–255) → Result<(), E>
    let flash_start = Instant::now();
    while flash_start.elapsed().as_secs() < FLASH_SECS {
        println!("mode=warm-white brightness={}", strip.get_brightness());
        sleep(Duration::from_millis(100));
    }

    // --- Dim warm white to 50% using the brightness property and hold for
    //     2 seconds. Demonstrates that brightness scaling is non-destructive:
    //     the stored RGBW values are unchanged, only the scale factor applied
    //     at show() time changes. ---
    strip.set_brightness(128);                                      // Set global brightness, (value=0–255) → ()
    strip.show().expect("show dimmed");                             // Transmit buffer to strip, () → Result<(), E>
    let dim_start = Instant::now();
    while dim_start.elapsed().as_secs() < DIM_SECS {
        println!("mode=warm-white-dimmed brightness={}", strip.get_brightness());
        sleep(Duration::from_millis(100));
    }

    // --- Cycle to cool white at full brightness. r=200, g=210, b=255,
    //     w=255 shifts the blend toward blue, showcasing the dedicated
    //     white element paired with a cool-tinted RGB base. ---
    strip.set_brightness(255);                                      // Set global brightness, (value=0–255) → ()
    strip.fill(200, 210, 255, 255).expect("fill cool white");       // Fill all pixels cool white, (r=0–255, g=0–255, b=0–255, w=0–255) → Result<(), E>
    println!("mode=cool-white brightness={}", strip.get_brightness());
}
