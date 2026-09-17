//! APA102 complete example — every API method demonstrated.
use linux_embedded_hal::SpidevBus;
use periph::connection::spi::SpiConnection;
use periph::chips::led::Apa102Full;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let bus = std::env::var("SPI_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let device = std::env::var("SPI_DEVICE").ok().and_then(|v| v.parse().ok()).unwrap_or(0);

    let mut spidev = linux_embedded_hal::Spidev::open(format!("/dev/spidev{bus}.{device}")).unwrap();
    spidev.configure(
        &linux_embedded_hal::SpidevOptions::new()
            .max_speed_hz(1_000_000)
            .mode(spidev::SpiModeFlags::SPI_MODE_0)
            .build(),
    ).unwrap();
    let spi_bus = SpidevBus(spidev);
    let spi = embedded_hal_bus::spi::ExclusiveDevice::new_no_delay(spi_bus, None).unwrap();

    let mut strip = Apa102Full::new(spi, 8);

    // fill — set all pixels and send immediately
    strip.fill(255, 0, 0).unwrap();                                 // Fill all pixels with one colour, (r=0–255, g=0–255, b=0–255) → ()
                                                                      // stores brightness/B/G/R in buffer and calls connection.write()
    sleep(Duration::from_millis(500));

    // set individual pixels then show
    strip.set_pixel(0, 255, 0, 0, 31);                              // Set pixel 0 to red (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → ()
                                                                      // writes brightness, B, G, R bytes into internal buffer at position index*4
    strip.set_pixel(1, 0, 255, 0, 31);                              // Set pixel 1 to green (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → ()
                                                                      // writes brightness, B, G, R bytes into internal buffer at position index*4
    strip.set_pixel(2, 0, 0, 255, 31);                              // Set pixel 2 to blue (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → ()
                                                                      // writes brightness, B, G, R bytes into internal buffer at position index*4
    strip.show().unwrap();                                          // Transmit buffer to strip, () → ()
                                                                      // applies software brightness scaling then calls connection.write()
    sleep(Duration::from_millis(500));

    // set_pixels — write multiple pixels at once
    strip.set_pixels(&[
        &[255, 128, 0],   &[128, 0, 255],   &[0, 255, 128],
        &[255, 255, 0],   &[0, 255, 255],   &[255, 0, 255],
        &[128, 128, 128], &[255, 255, 255],
    ]);                                                             // Set pixels from array of [r,g,b], (colors=Array<[r,g,b]>) → ()
                                                                      // writes entries sequentially from pixel 0; ignores extras beyond strip length
    strip.show().unwrap();                                          // Transmit buffer to strip, () → ()
                                                                      // applies software brightness scaling then calls connection.write()
    sleep(Duration::from_millis(500));

    // set_pixels with per-pixel hardware brightness
    strip.set_pixels(&[
        &[255, 0, 0, 31], &[255, 0, 0, 16], &[255, 0, 0, 8],  &[255, 0, 0, 4],
        &[0, 255, 0, 31], &[0, 255, 0, 16], &[0, 255, 0, 8],  &[0, 255, 0, 4],
    ]);                                                             // Set pixels with varying hardware brightness, (colors=Array<[r,g,b,brightness]>) → ()
                                                                      // writes entries sequentially from pixel 0; ignores extras beyond strip length
    strip.show().unwrap();                                          // Transmit buffer to strip, () → ()
                                                                      // applies software brightness scaling then calls connection.write()
    sleep(Duration::from_millis(500));

    // brightness — global software scale applied at show() time
    strip.set_brightness(64);                                       // Set global software brightness, (value=0–255) → ()
                                                                      // stored RGB value is scaled: sent = stored * brightness / 255; hardware brightness byte unchanged
    strip.show().unwrap();                                          // Transmit buffer to strip, () → ()
                                                                      // applies software brightness scaling then calls connection.write()
    sleep(Duration::from_millis(500));
    strip.set_brightness(255);                                      // Set global software brightness, (value=0–255) → ()
                                                                      // stored RGB value is scaled: sent = stored * brightness / 255; hardware brightness byte unchanged

    // fill_hsv — fill all pixels from HSV colour
    strip.fill_hsv(0.0, 1.0, 1.0).unwrap();                         // Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → ()
                                                                      // converts HSV to RGB then calls fill(); hue 0.0 = red
    sleep(Duration::from_millis(500));
    strip.fill_hsv(0.333, 1.0, 1.0).unwrap();                       // Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → ()
                                                                      // converts HSV to RGB then calls fill(); hue 0.333 = green
    sleep(Duration::from_millis(500));
    strip.fill_hsv(0.667, 1.0, 1.0).unwrap();                       // Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → ()
                                                                      // converts HSV to RGB then calls fill(); hue 0.667 = blue
    sleep(Duration::from_millis(500));

    // rotate — shift pixel buffer left, then show
    strip.set_pixels(&[&[255, 0, 0], &[0, 0, 0], &[0, 0, 0], &[0, 0, 0],
                       &[0, 0, 0], &[0, 0, 0], &[0, 0, 0], &[0, 0, 0]]); // Set pixels from array of [r,g,b], (colors=Array<[r,g,b]>) → ()
                                                                      // writes entries sequentially from pixel 0; ignores extras beyond strip length
    strip.show().unwrap();                                          // Transmit buffer to strip, () → ()
                                                                      // applies software brightness scaling then calls connection.write()
    sleep(Duration::from_millis(500));
    for _ in 0..7 {
        strip.rotate(1);                                            // Rotate pixel buffer left, (steps=1) → ()
                                                                      // shifts buffer by steps pixel positions; wraps around; does not send
        strip.show().unwrap();                                      // Transmit buffer to strip, () → ()
                                                                      // applies software brightness scaling then calls connection.write()
        sleep(Duration::from_millis(200));
    }

    strip.off().unwrap();                                           // Turn off all pixels, () → ()
                                                                      // equivalent to fill(0, 0, 0)
    sleep(Duration::from_secs(1));
}