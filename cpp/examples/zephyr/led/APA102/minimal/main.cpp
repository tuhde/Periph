#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <zephyr/drivers/spi.h>
#include "SPIConnectionZephyr.h"
#include "APA102.h"

#define SPI_NODE DT_NODELABEL(spi0)

int main(void) {
    const struct device *spi_dev = DEVICE_DT_GET(SPI_NODE);

    struct spi_config spi_cfg = {
        .frequency = 1000000,
        .operation = SPI_WORD_SET(8) | SPI_TRANSFER_MSB | SPI_OP_MODE_MASTER,
        .cs = {},
        .slave = 0,
    };

    SPIConnectionZephyr connection(spi_dev, spi_cfg);                    // Create SPI connection, (dev=spi_device*, config)
    APA102Minimal strip(connection, 30);                                 // Create APA102 driver, (connection, n=30 pixels)

    while (1) {
        strip.fill(255, 0, 0);                                            // Fill all pixels red, (r=0–255, g=0–255, b=0–255) → void
        k_sleep(K_SECONDS(1));
        strip.fill(0, 255, 0);                                            // Fill all pixels green, (r=0–255, g=0–255, b=0–255) → void
        k_sleep(K_SECONDS(1));
        strip.fill(0, 0, 255);                                            // Fill all pixels blue, (r=0–255, g=0–255, b=0–255) → void
        k_sleep(K_SECONDS(1));
        strip.off();                                                      // Turn off all pixels, () → void
        k_sleep(K_SECONDS(1));
    }
    return 0;
}