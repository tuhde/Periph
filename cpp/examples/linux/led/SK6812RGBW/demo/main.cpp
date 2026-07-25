#include <cstdio>
#include <unistd.h>
#include "NeoPixelTransportLinux.h"
#include "SK6812RGBW.h"

int main() {
    NeoPixelTransportLinux transport(0, 0);
    SK6812RGBWFull strip(transport, 30);                                   // Create SK6812RGBW full driver, (transport, n=30 pixels)

    // --- Warm-white breathing effect using W channel ---
    strip.set_brightness(255);                                             // Set global brightness 0–255, (brightness) → void
    while (true) {
        for (int b = 0; b <= 255; b++) {
            strip.fill(0, 0, 0, (uint8_t)b);                               // Fill with white-channel only, (r=0, g=0, b=0, w) → void
            usleep(4000);
        }
        for (int b = 255; b >= 0; b--) {
            strip.fill(0, 0, 0, (uint8_t)b);                               // Fill with white-channel only, (r=0, g=0, b=0, w) → void
            usleep(4000);
        }
    }
    return 0;
}
