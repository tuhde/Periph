// Auto-generated Pico SDK example for APA102 (Complete).
// Uses SPIConnectionPicoSDK for raw SPI Mode 0 (APA102 synchronous protocol).

#include <stdio.h>
#include <pico/stdlib.h>
#include <hardware/spi.h>
#include "SPIConnectionPicoSDK.h"
#include "APA102.h"

int main(void) {
    // SPI0 on GP3 (MOSI/TX), GP2 (SCK), no MISO needed
    spi_init(spi0, 1'000'000);  // 1 MHz for APA102
    gpio_set_function(3, GPIO_FUNC_SPI);  // MOSI
    gpio_set_function(2, GPIO_FUNC_SPI);  // SCK

    SPIConnectionPicoSDK connection(spi0, 10);  // CS on GP10 (not used by APA102)
    APA102Full strip(connection, 8);            // Create APA102 full driver

    stdio_init_all();
    while (true) {
        // fill — set all pixels and send immediately
        strip.fill(255, 0, 0);                                  // Fill all pixels with one colour, (r=0–255, g=0–255, b=0–255) → void
                                                                 // stores brightness/B/G/R in buffer and calls connection.write()
        sleep_ms(500);

        // set individual pixels then show
        strip.set_pixel(0, 255, 0, 0);                          // Set pixel 0 to red (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → void
                                                                 // writes brightness, B, G, R bytes into internal buffer at position index*4
        strip.set_pixel(1, 0, 255, 0);                          // Set pixel 1 to green (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → void
                                                                 // writes brightness, B, G, R bytes into internal buffer at position index*4
        strip.set_pixel(2, 0, 0, 255);                          // Set pixel 2 to blue (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → void
                                                                 // writes brightness, B, G, R bytes into internal buffer at position index*4
        strip.show();                                           // Transmit buffer to strip, () → void
                                                                 // applies software brightness scaling then calls connection.write()
        sleep_ms(500);

        // set_pixels — write multiple pixels at once
        uint8_t colors[] = {
            255, 128, 0,   128, 0, 255,   0, 255, 128,
            255, 255, 0,   0, 255, 255,   255, 0, 255,
            128, 128, 128, 255, 255, 255
        };
        strip.set_pixels(colors, 8, false);                     // Set pixels from flat array of (r,g,b), (colors=uint8_t[], count, has_brightness=false) → void
                                                                 // writes entries sequentially from pixel 0; ignores extras beyond strip length
        strip.show();                                           // Transmit buffer to strip, () → void
                                                                 // applies software brightness scaling then calls connection.write()
        sleep_ms(500);

        // set_pixels with per-pixel hardware brightness
        uint8_t colors_bright[] = {
            255, 0, 0, 31,   255, 0, 0, 16,   255, 0, 0, 8,   255, 0, 0, 4,
            0, 255, 0, 31,   0, 255, 0, 16,   0, 255, 0, 8,   0, 255, 0, 4
        };
        strip.set_pixels(colors_bright, 8, true);               // Set pixels from flat array of (r,g,b,brightness), (colors=uint8_t[], count, has_brightness=true) → void
                                                                 // writes entries sequentially from pixel 0; ignores extras beyond strip length
        strip.show();                                           // Transmit buffer to strip, () → void
                                                                 // applies software brightness scaling then calls connection.write()
        sleep_ms(500);

        // brightness — global software scale applied at show() time
        strip.set_brightness(64);                               // Set global software brightness, (value=0–255) → void
                                                                 // stored RGB value is scaled: sent = stored * brightness / 255; hardware brightness byte unchanged
        strip.show();                                           // Transmit buffer to strip, () → void
                                                                 // applies software brightness scaling then calls connection.write()
        sleep_ms(500);
        strip.set_brightness(255);                              // Set global software brightness, (value=0–255) → void
                                                                 // stored RGB value is scaled: sent = stored * brightness / 255; hardware brightness byte unchanged

        // fill_hsv — fill all pixels from HSV colour
        strip.fill_hsv(0.0f, 1.0f, 1.0f);                       // Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → void
                                                                 // converts HSV to RGB then calls fill(); hue 0.0 = red
        sleep_ms(500);
        strip.fill_hsv(0.333f, 1.0f, 1.0f);                     // Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → void
                                                                 // converts HSV to RGB then calls fill(); hue 0.333 = green
        sleep_ms(500);
        strip.fill_hsv(0.667f, 1.0f, 1.0f);                     // Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → void
                                                                 // converts HSV to RGB then calls fill(); hue 0.667 = blue
        sleep_ms(500);

        // rotate — shift pixel buffer by N positions
        strip.set_pixel(0, 255, 0, 0);                          // Set pixel 0 in buffer (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → void
                                                                 // writes brightness, B, G, R bytes into internal buffer at position index*4
        for (size_t i = 1; i < 8; i++) {
            strip.set_pixel(i, 0, 0, 0);                        // Set pixel i in buffer (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → void
                                                                 // writes brightness, B, G, R bytes into internal buffer at position index*4
        }
        strip.show();                                           // Transmit buffer to strip, () → void
                                                                 // applies software brightness scaling then calls connection.write()
        sleep_ms(500);
        for (int i = 0; i < 7; i++) {
            strip.rotate(1);                                    // Rotate pixel buffer left, (steps=1) → void
                                                                 // shifts buffer by steps pixel positions; wraps around; does not send
            strip.show();                                       // Transmit buffer to strip, () → void
                                                                 // applies software brightness scaling then calls connection.write()
            sleep_ms(200);
        }

        strip.off();                                            // Turn off all pixels, () → void
                                                                 // equivalent to fill(0, 0, 0)
        sleep_ms(1000);
    }

    return 0;
}