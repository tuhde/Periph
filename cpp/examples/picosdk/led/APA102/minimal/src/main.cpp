// Auto-generated Pico SDK example for APA102 (Minimal).
// Uses SPIConnectionPicoSDK for raw SPI Mode 0 (APA102 synchronous protocol).

#include <stdio.h>
#include <pico/stdlib.h>
#include <hardware/spi.h>
#include "SPIConnectionPicoSDK.h"
#include "APA102.h"

int main(void) {
    // SPI0 on GP4 (SDA/MOSI), GP5 (SCL/SCK) — default Pico SDK I2C pins;
    // for APA102 SPI: GP3 (MOSI/TX), GP2 (SCK), no MISO needed
    spi_init(spi0, 1'000'000);  // 1 MHz for APA102
    gpio_set_function(3, GPIO_FUNC_SPI);  // MOSI
    gpio_set_function(2, GPIO_FUNC_SPI);  // SCK
    // GP4 (MISO) not used by APA102

    SPIConnectionPicoSDK connection(spi0, 10);  // CS on GP10 (not used by APA102)
    APA102Minimal strip(connection, 30);        // Create APA102 driver

    stdio_init_all();
    while (true) {
        strip.fill(255, 0, 0);                      // Fill all pixels red, (r=0–255, g=0–255, b=0–255) → void
        sleep_ms(1000);
        strip.fill(0, 255, 0);                      // Fill all pixels green, (r=0–255, g=0–255, b=0–255) → void
        sleep_ms(1000);
        strip.fill(0, 0, 255);                      // Fill all pixels blue, (r=0–255, g=0–255, b=0–255) → void
        sleep_ms(1000);
        strip.off();                                // Turn off all pixels, () → void
        sleep_ms(1000);
    }

    return 0;
}