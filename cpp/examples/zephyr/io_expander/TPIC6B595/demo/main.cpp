// TPIC6B595 demo — "knight rider" chase pattern across two cascaded devices.
#include <zephyr/kernel.h>
#include <zephyr/sys/printk.h>
#include <zephyr/drivers/spi.h>
#include "SiPoConnectionZephyr.h"
#include "TPIC6B595.h"

static constexpr uint8_t NUM_DEVICES = 2;
static constexpr uint8_t NUM_OUTPUTS = NUM_DEVICES * 8;

int main() {
    const struct device* spi_dev = DEVICE_DT_GET(DT_NODELABEL(spi0));

    struct spi_config spi_cfg = {
        .frequency = 1000000,
        .operation = SPI_WORD_SET(8) | SPI_TRANSFER_MSB | SPI_OP_MODE_MASTER | SPI_MODE_CPOL | SPI_MODE_CPHA,
        .slave     = 0,
        .cs        = {},
    };

    const struct gpio_dt_spec rck = GPIO_DT_SPEC_GET(DT_ALIAS(sipo_rck), gpios);
    SiPoConnectionZephyr connection(spi_dev, spi_cfg, rck);        // Create SiPo connection, (dev, config, rck, srclr={}, g={})
    TPIC6B595Full<SiPoConnectionZephyr> chip(connection, NUM_DEVICES); // Create TPIC6B595 full driver, (connection, num_devices=2)
                                                                       // two cascaded devices — 16 outputs total; outputs start OFF

    int8_t position = 0;
    int8_t direction = 1;
    uint8_t sweep_count = 0;
    constexpr uint8_t BLANK_EVERY = 3;
    constexpr int32_t BLANK_MS = 500;

    while (true) {
        // --- Walk a single lit LED across all 16 outputs and back ---
        // Use write_all() each step so both cascaded devices latch together —
        // there is no way to update just one downstream device without re-sending
        // the whole chain's data.
        uint8_t bytes_[NUM_DEVICES] = {0, 0};
        uint8_t port = position / 8;
        uint8_t bit  = position % 8;
        bytes_[port] = (uint8_t)(1u << bit);
        chip.write_all(bytes_, NUM_DEVICES);                       // Write all device bytes, (values=uint8_t*, len=2) → void

        printk("position=%d  bytes=[0x%02X, 0x%02X]\n",
               (int)position, bytes_[0], bytes_[1]);

        // --- Periodically blank every output via G, then resume ---
        // set_output_enable(false) drives G HIGH, forcing every DMOS off without
        // touching the shadow register — the LEDs simply resume exactly where they
        // left off when G is re-enabled.
        sweep_count++;
        if (sweep_count % BLANK_EVERY == 0) {
            chip.set_output_enable(false);                          // Force every output off via G, (enabled=false) → int
                                                                      // the chase pattern's shadow state is preserved
            printk("  blanked via G for %d ms\n", (int)BLANK_MS);
            k_msleep(BLANK_MS);
            chip.set_output_enable(true);                           // Re-enable outputs, (enabled=true) → int
                                                                      // LEDs resume from the previously-latched state
        }

        // Bounce the chase position at both ends of the strip
        position += direction;
        if (position >= (int8_t)(NUM_OUTPUTS - 1) || position <= 0) {
            direction = -direction;
            k_msleep(100);
        } else {
            k_msleep(80);
        }
    }
    return 0;
}
