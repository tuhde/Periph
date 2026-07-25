#include <cstdio>
#include <unistd.h>
#include "NeoPixelTransportLinux.h"
#include "SK6812RGBW.h"

int main() {
    NeoPixelTransportLinux transport(0, 0);
    SK6812RGBWFull strip(transport, 8);                                    // Create SK6812RGBW full driver, (transport, n=8 pixels)

    strip.fill(255, 0, 0, 0);                                              // Fill all pixels red, (r, g, b, w) → void
    usleep(500000);
    strip.set_pixel(0, 255, 0, 0, 0);                                      // Set pixel 0 in buffer, (index, r, g, b, w) → void
    strip.set_pixel(1, 0, 0, 0, 255);                                      // Set pixel 1 white channel, (index, r, g, b, w) → void
    strip.show();                                                          // Transmit buffer to strip, () → void
    usleep(500000);
    strip.set_brightness(64);                                              // Set global brightness 0–255, (brightness) → void
    strip.show();                                                          // Apply brightness and transmit, () → void
    strip.off();                                                           // Turn off all pixels, () → void
    return 0;
}
