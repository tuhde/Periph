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

    let mut strip = Ws2812bFull::new(spi, 8);                    // Create WS2812B full driver, (spi, n=8 pixels) → Self
                                                                 // allocates internal GRB buffer of n*3 bytes; default brightness = 255

    // fill — set all pixels and send immediately
    strip.fill(255, 0, 0).expect("fill");                        // Fill all pixels with one colour, (r=0–255, g=0–255, b=0–255) → Result<(), E>
                                                                 // stores GRB in buffer and calls NeoPixelTransport::write()
    delay.delay_ms(500);

    // set individual pixels then show
    strip.set_pixel(0, 255, 0, 0);                               // Set pixel 0 in buffer (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255) → ()
                                                                 // writes G,R,B bytes into internal buffer at position index*3
    strip.set_pixel(1, 0, 255, 0);                               // Set pixel 1 in buffer (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255) → ()
                                                                 // writes G,R,B bytes into internal buffer at position index*3
    strip.set_pixel(2, 0, 0, 255);                               // Set pixel 2 in buffer (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255) → ()
                                                                 // writes G,R,B bytes into internal buffer at position index*3
    strip.show().expect("show");                                 // Transmit buffer to strip, () → Result<(), E>
                                                                 // applies brightness scaling then calls NeoPixelTransport::write()
    delay.delay_ms(500);

    // brightness
    strip.set_brightness(64);                                    // Set global brightness, (value=0–255) → ()
                                                                 // stored value is scaled: sent = stored * brightness / 255
    strip.show().expect("show dimmed");                          // Transmit buffer to strip, () → Result<(), E>
                                                                 // applies brightness scaling then calls NeoPixelTransport::write()
    delay.delay_ms(500);
    strip.set_brightness(255);                                   // Set global brightness, (value=0–255) → ()
                                                                 // stored value is scaled: sent = stored * brightness / 255

    // fill_hsv
    strip.fill_hsv(0.0, 1.0, 1.0).expect("fill hsv red");       // Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → Result<(), E>
                                                                 // converts HSV to RGB then calls fill(); hue 0.0 = red
    delay.delay_ms(500);
    strip.fill_hsv(0.333, 1.0, 1.0).expect("fill hsv green");   // Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → Result<(), E>
                                                                 // converts HSV to RGB then calls fill(); hue 0.333 = green
    delay.delay_ms(500);
    strip.fill_hsv(0.667, 1.0, 1.0).expect("fill hsv blue");    // Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → Result<(), E>
                                                                 // converts HSV to RGB then calls fill(); hue 0.667 = blue
    delay.delay_ms(500);

    // rotate
    strip.set_pixel(0, 255, 0, 0);                               // Set pixel 0 in buffer (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255) → ()
                                                                 // writes G,R,B bytes into internal buffer at position index*3
    for i in 1..8 {
        strip.set_pixel(i, 0, 0, 0);                             // Set pixel i in buffer (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255) → ()
                                                                 // writes G,R,B bytes into internal buffer at position index*3
    }
    strip.show().expect("show before rotate");                   // Transmit buffer to strip, () → Result<(), E>
                                                                 // applies brightness scaling then calls NeoPixelTransport::write()
    delay.delay_ms(500);
    for _ in 0..7 {
        strip.rotate(1);                                         // Rotate pixel buffer left, (steps=1) → ()
                                                                 // shifts buffer by steps pixel positions; wraps around; does not send
        strip.show().expect("show rotated");                     // Transmit buffer to strip, () → Result<(), E>
                                                                 // applies brightness scaling then calls NeoPixelTransport::write()
        delay.delay_ms(200);
    }

    strip.off().expect("off");                                   // Turn off all pixels, () → Result<(), E>
                                                                 // equivalent to fill(0, 0, 0)
    println!("WS2812B complete example done");
    loop {}
}
