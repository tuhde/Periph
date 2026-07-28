#include <cstdio>
#include <unistd.h>
#include "NeoPixelConnectionLinux.h"
#include "WS2812B.h"

int main() {
    NeoPixelConnectionLinux connection(0, 0);
    WS2812BFull strip(connection, 30);                                      // Create WS2812B full driver, (connection, n=30 pixels)

    // --- Rainbow chaser ---
    // Cycles hue across the strip continuously.
    strip.set_brightness(80);                                              // Set global brightness 0–255, (brightness) → void
    while (true) {
        for (int offset = 0; offset < 256; offset++) {
            for (int i = 0; i < 30; i++) {
                int hue = (i * 256 / 30 + offset) & 0xFF;
                // Simple HSV→RGB (S=V=1)
                int s = 6 * hue;
                int r = s < 256 ? 255 : s < 512 ? 511-s : s < 768 ? 0 : s < 1024 ? s-768 : 255;
                int g = s < 256 ? s : s < 512 ? 255 : s < 768 ? 767-s : 0;
                int b = s < 512 ? 0 : s < 768 ? s-512 : s < 1024 ? 255 : 1279-s;
                strip.set_pixel(i, r&0xFF, g&0xFF, b&0xFF);               // Set pixel in buffer, (index, r, g, b) → void
            }
            strip.show();                                                  // Transmit buffer to strip, () → void
            usleep(20000);
        }
    }
    return 0;
}
