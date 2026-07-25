#include <cstdio>
#include <unistd.h>
#include "NeoPixelTransportLinux.h"
#include "SK6812RGBW.h"

int main() {
    NeoPixelTransportLinux transport(0, 0);
    SK6812RGBWMinimal strip(transport, 30);                                // Create SK6812RGBW driver, (transport, n=30 pixels)

    strip.fill(255, 0, 0, 0);                                              // Fill all pixels red, (r=0–255, g=0–255, b=0–255, w=0–255) → void
    usleep(1000000);
    strip.fill(0, 0, 0, 255);                                              // Fill all pixels warm white, (r, g, b, w) → void
    usleep(1000000);
    strip.off();                                                           // Turn off all pixels, () → void
    return 0;
}
