#include <cstdio>
#include <unistd.h>
#include "NeoPixelConnectionLinux.h"
#include "WS2812B.h"

int main() {
    NeoPixelConnectionLinux connection(0, 0);
    WS2812BFull strip(connection, 8);                                       // Create WS2812B full driver, (connection, n=8 pixels)

    strip.fill(255, 0, 0);                                                 // Fill all pixels with one colour, (r=0–255, g=0–255, b=0–255) → void
    usleep(500000);
    strip.set_pixel(0, 255, 0, 0);                                         // Set pixel 0 in buffer (no send), (index, r, g, b) → void
    strip.set_pixel(1, 0, 255, 0);                                         // Set pixel 1 in buffer, (index, r, g, b) → void
    strip.set_pixel(2, 0, 0, 255);                                         // Set pixel 2 in buffer, (index, r, g, b) → void
    strip.show();                                                          // Transmit buffer to strip, () → void
    usleep(500000);
    strip.set_brightness(64);                                              // Set global brightness 0–255, (brightness) → void
    strip.show();                                                          // Apply brightness and transmit, () → void
    usleep(500000);
    strip.off();                                                           // Turn off all pixels, () → void
    return 0;
}
