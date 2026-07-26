#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "NeoPixelTransportPicoSDK.h"
#include "SK6812RGBW.h"

int main(void) {
    // SPI0 on GP3 (MOSI) — NeoPixel DIN must be on the SPI MOSI pin;
    // SCK, MISO, and CS are unused by the strip.
    spi_init(spi0, 2'400'000);
    gpio_set_function(3, GPIO_FUNC_SPI);
    NeoPixelTransportPicoSDK transport(spi0);
    SK6812RGBWFull strip(transport, /*n_pixels=*/8);

    stdio_init_all();
    while (true) {

    // fill — set all pixels and send immediately
    strip.fill(255, 0, 0);                      // Fill all pixels with one colour, (r=0–255, g=0–255, b=0–255, w=0–255) → void
                                                // stores GRBW in buffer and calls transport.write()
    sleep_ms(500);

    // fill with white channel
    strip.fill(0, 0, 0, 255);                   // Fill all pixels using W channel, (r=0–255, g=0–255, b=0–255, w=0–255) → void
                                                // w=255 activates dedicated white LED element; RGB channels stay dark
    sleep_ms(500);

    // set individual pixels then show
    strip.set_pixel(0, 255, 0, 0);             // Set pixel 0 in buffer (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, w=0–255) → void
                                                // writes G,R,B,W bytes into internal buffer at position index*4
    strip.set_pixel(1, 0, 255, 0);             // Set pixel 1 in buffer (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, w=0–255) → void
                                                // writes G,R,B,W bytes into internal buffer at position index*4
    strip.set_pixel(2, 0, 0, 255);             // Set pixel 2 in buffer (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, w=0–255) → void
                                                // writes G,R,B,W bytes into internal buffer at position index*4
    strip.set_pixel(3, 0, 0, 0, 255);          // Set pixel 3 to white in buffer (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, w=0–255) → void
                                                // w=255 lights the dedicated white element; RGB remain off
    strip.show();                               // Transmit buffer to strip, () → void
                                                // applies brightness scaling then calls transport.write()
    sleep_ms(500);

    // brightness — global scale applied at show() time
    strip.set_brightness(64);                   // Set global brightness, (value=0–255) → void
                                                // stored value is scaled: sent = stored * brightness / 255
    strip.show();                               // Transmit buffer to strip, () → void
                                                // applies brightness scaling then calls transport.write()
    sleep_ms(500);
    strip.set_brightness(255);                  // Set global brightness, (value=0–255) → void
                                                // stored value is scaled: sent = stored * brightness / 255

    // fill_hsv — fill all pixels from HSV colour (white=0)
    strip.fill_hsv(0.0f, 1.0f, 1.0f);         // Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → void
                                                // converts HSV to RGB (w=0) then calls fill(); hue 0.0 = red
    sleep_ms(500);
    strip.fill_hsv(0.333f, 1.0f, 1.0f);       // Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → void
                                                // converts HSV to RGB (w=0) then calls fill(); hue 0.333 = green
    sleep_ms(500);
    strip.fill_hsv(0.667f, 1.0f, 1.0f);       // Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → void
                                                // converts HSV to RGB (w=0) then calls fill(); hue 0.667 = blue
    sleep_ms(500);

    // rotate — shift pixel buffer left, then show
    strip.set_pixel(0, 255, 0, 0);             // Set pixel 0 in buffer (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, w=0–255) → void
                                                // writes G,R,B,W bytes into internal buffer at position index*4
    for (size_t i = 1; i < 8; i++) {
        strip.set_pixel(i, 0, 0, 0, 0);        // Set pixel i in buffer (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, w=0–255) → void
                                                // writes G,R,B,W bytes into internal buffer at position index*4
    }
    strip.show();                               // Transmit buffer to strip, () → void
                                                // applies brightness scaling then calls transport.write()
    sleep_ms(500);
    for (int i = 0; i < 7; i++) {
        strip.rotate(1);                        // Rotate pixel buffer left, (steps=1) → void
                                                // shifts buffer by steps whole-pixel (4-byte) positions; wraps around; does not send
        strip.show();                           // Transmit buffer to strip, () → void
                                                // applies brightness scaling then calls transport.write()
        sleep_ms(200);
    }

    strip.off();                                // Turn off all pixels, () → void
                                                // equivalent to fill(0, 0, 0, 0)
    sleep_ms(1000);
        sleep_ms(10);
    }

    return 0;
}
