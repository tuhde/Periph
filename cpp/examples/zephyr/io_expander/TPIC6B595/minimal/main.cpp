#include <zephyr/kernel.h>
#include <zephyr/sys/printk.h>
#include <zephyr/drivers/spi.h>
#include "SiPoConnectionZephyr.h"
#include "TPIC6B595.h"

int main() {
    const struct device* spi_dev = DEVICE_DT_GET(DT_NODELABEL(spi0));

    struct spi_config spi_cfg = {
        .frequency = 1000000,
        .operation = SPI_WORD_SET(8) | SPI_TRANSFER_MSB | SPI_OP_MODE_MASTER | SPI_MODE_CPOL | SPI_MODE_CPHA,
        .slave     = 0,
        .cs        = {},
    };

    const struct gpio_dt_spec rck   = GPIO_DT_SPEC_GET(DT_ALIAS(sipo_rck), gpios);
    SiPoConnectionZephyr connection(spi_dev, spi_cfg, rck);        // Create SiPo connection, (dev, config, rck, srclr={}, g={})
    TPIC6B595Minimal<SiPoConnectionZephyr> chip(connection);       // Create TPIC6B595 driver, (connection, num_devices=1)
                                                                    // initialises every output to OFF (shadow zeroed, latched once)

    auto p0 = chip.pin(0);                                         // Get pin proxy, (n=0) → IOExpanderPin
    auto p7 = chip.pin(7);                                         // Get pin proxy, (n=7) → IOExpanderPin

    while (true) {
        p0.high();                                                 // Set DMOS output ON, () → void
        p7.low();                                                  // Set DMOS output OFF, () → void
        k_msleep(500);
        p0.low();                                                  // Set DMOS output OFF, () → void
        p7.high();                                                 // Set DMOS output ON, () → void
        k_msleep(500);
    }
    return 0;
}
