#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "NeoPixelTransportZephyr.h"
#include "WS2812B.h"

#define SPI_NODE DT_NODELABEL(spi0)

int main(void) {
    const struct device *spi_dev = DEVICE_DT_GET(SPI_NODE);
    NeoPixelTransportZephyr transport(spi_dev);    // Create NeoPixel transport, (dev=spi_device*)
    WS2812BFull strip(transport, 8);               // Create WS2812B full driver, (transport, n=8 pixels)

    // fill — set all pixels and send immediately
    strip.fill(255, 0, 0);                         // Fill all pixels with one colour, (r=0–255, g=0–255, b=0–255) → void
                                                   // stores GRB in buffer and calls transport.write()
    k_sleep(K_MSEC(500));

    // set individual pixels then show
    strip.set_pixel(0, 255, 0, 0);                // Set pixel 0 in buffer (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255) → void
                                                   // writes G,R,B bytes into internal buffer at position index*3
    strip.set_pixel(1, 0, 255, 0);                // Set pixel 1 in buffer (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255) → void
                                                   // writes G,R,B bytes into internal buffer at position index*3
    strip.set_pixel(2, 0, 0, 255);                // Set pixel 2 in buffer (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255) → void
                                                   // writes G,R,B bytes into internal buffer at position index*3
    strip.show();                                  // Transmit buffer to strip, () → void
                                                   // applies brightness scaling then calls transport.write()
    k_sleep(K_MSEC(500));

    // brightness
    strip.set_brightness(64);                      // Set global brightness, (value=0–255) → void
                                                   // stored value is scaled: sent = stored * brightness / 255
    strip.show();                                  // Transmit buffer to strip, () → void
                                                   // applies brightness scaling then calls transport.write()
    k_sleep(K_MSEC(500));
    strip.set_brightness(255);                     // Set global brightness, (value=0–255) → void
                                                   // stored value is scaled: sent = stored * brightness / 255

    // fill_hsv
    strip.fill_hsv(0.0f, 1.0f, 1.0f);            // Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → void
                                                   // converts HSV to RGB then calls fill(); hue 0.0 = red
    k_sleep(K_MSEC(500));
    strip.fill_hsv(0.333f, 1.0f, 1.0f);          // Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → void
                                                   // converts HSV to RGB then calls fill(); hue 0.333 = green
    k_sleep(K_MSEC(500));
    strip.fill_hsv(0.667f, 1.0f, 1.0f);          // Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → void
                                                   // converts HSV to RGB then calls fill(); hue 0.667 = blue
    k_sleep(K_MSEC(500));

    // rotate
    strip.set_pixel(0, 255, 0, 0);                // Set pixel 0 in buffer (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255) → void
                                                   // writes G,R,B bytes into internal buffer at position index*3
    for (size_t i = 1; i < 8; i++) {
        strip.set_pixel(i, 0, 0, 0);              // Set pixel i in buffer (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255) → void
                                                   // writes G,R,B bytes into internal buffer at position index*3
    }
    strip.show();                                  // Transmit buffer to strip, () → void
                                                   // applies brightness scaling then calls transport.write()
    k_sleep(K_MSEC(500));
    for (int i = 0; i < 7; i++) {
        strip.rotate(1);                           // Rotate pixel buffer left, (steps=1) → void
                                                   // shifts buffer by steps pixel positions; wraps around; does not send
        strip.show();                              // Transmit buffer to strip, () → void
                                                   // applies brightness scaling then calls transport.write()
        k_sleep(K_MSEC(200));
    }

    strip.off();                                   // Turn off all pixels, () → void
                                                   // equivalent to fill(0, 0, 0)

    while (1) k_sleep(K_FOREVER);
    return 0;
}
