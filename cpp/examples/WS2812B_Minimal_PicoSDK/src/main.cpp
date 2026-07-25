#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "NeoPixelTransportPicoSDK.h"
#include "WS2812B.h"

// SPI0 on GP3 (MOSI) — NeoPixel DIN must be on the SPI MOSI pin;
// SCK, MISO, and CS are unused by the strip.
spi_init(spi0, 2'400'000);
gpio_set_function(3, GPIO_FUNC_SPI);
NeoPixelTransportPicoSDK transport(spi0);
WS2812BMinimal strip(transport, /*n_pixels=*/8);

int main(void) {

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
        sleep_ms(10);
    }

    return 0;
}
