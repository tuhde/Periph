#include <cstdio>
#include <unistd.h>
#include "NeoPixelTransportLinux.h"
#include "WS2812B.h"

int main() {
    NeoPixelTransportLinux transport(0, 0);
    WS2812BMinimal strip(transport, 30);                                   // Create WS2812B driver, (transport, n=30 pixels)

    strip.fill(255, 0, 0);                                                 // Fill all pixels red, (r=0–255, g=0–255, b=0–255) → void
    usleep(1000000);
    strip.fill(0, 255, 0);                                                 // Fill all pixels green, (r, g, b) → void
    usleep(1000000);
    strip.fill(0, 0, 255);                                                 // Fill all pixels blue, (r, g, b) → void
    usleep(1000000);
    strip.off();                                                           // Turn off all pixels, () → void
    return 0;
}
