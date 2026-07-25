#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "NeoPixelTransportZephyr.h"
#include "WS2812B.h"

#define SPI_NODE DT_NODELABEL(spi0)

int main(void) {
    const struct device *spi_dev = DEVICE_DT_GET(SPI_NODE);
    NeoPixelTransportZephyr transport(spi_dev);    // Create NeoPixel transport, (dev=spi_device*)
    WS2812BMinimal strip(transport, 30);           // Create WS2812B driver, (transport, n=30 pixels)

    while (1) {
        strip.fill(255, 0, 0);                     // Fill all pixels red, (r=0–255, g=0–255, b=0–255) → void
        k_sleep(K_SECONDS(1));
        strip.fill(0, 255, 0);                     // Fill all pixels green, (r=0–255, g=0–255, b=0–255) → void
        k_sleep(K_SECONDS(1));
        strip.fill(0, 0, 255);                     // Fill all pixels blue, (r=0–255, g=0–255, b=0–255) → void
        k_sleep(K_SECONDS(1));
        strip.off();                               // Turn off all pixels, () → void
        k_sleep(K_SECONDS(1));
    }
    return 0;
}
