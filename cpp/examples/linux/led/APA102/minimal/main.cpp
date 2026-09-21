#include <cstdio>
#include <unistd.h>
#include "SPIConnectionLinux.h"
#include "APA102.h"

int main() {
    SPIConnectionLinux connection(0, 0, 0, 1000000);                        // Create SPI connection, (bus=0, device=0, mode=0, 1MHz)
    APA102Minimal strip(connection, 30);                                     // Create APA102 driver, (connection, n=30 pixels)

    strip.fill(255, 0, 0);                                                    // Fill all pixels red, (r=0–255, g=0–255, b=0–255) → void
    usleep(1000000);
    strip.fill(0, 255, 0);                                                    // Fill all pixels green, (r, g, b) → void
    usleep(1000000);
    strip.fill(0, 0, 255);                                                    // Fill all pixels blue, (r, g, b) → void
    usleep(1000000);
    strip.off();                                                              // Turn off all pixels, () → void
    return 0;
}