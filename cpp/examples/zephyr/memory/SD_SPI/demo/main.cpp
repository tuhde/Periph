#include <zephyr/kernel.h>
#include <string.h>

#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "SPIConnectionZephyr.h"
#include "SD_SPI.h"

#ifndef SDSPI_SPI_NODE
#define SDSPI_SPI_NODE DT_NODELABEL(spi0)
#endif
#ifndef SDSPI_CS_GPIOS
#define SDSPI_CS_GPIOS GPIO_DT_SPEC_GET_BY_IDX(SDSPI_SPI_NODE, cs_gpios, 0)
#endif

#define PF printk
#define DELAY_MS(ms) k_msleep(ms)
static uint32_t now_ms() { return k_uptime_get_32(); }

#ifdef __AVR__
#define DEMO_BLOCKS 4
#else
#define DEMO_BLOCKS 64
#endif

static void app(SPIExtensions& spi) {
    // --- Bring up the card ---
    // The card starts in SD mode; the driver sends 80 clocks with CS high, CMD0 with CS
    // low to enter SPI mode, then negotiates the card type. SCK runs at 400 kHz or below
    // during init and is raised to the configured speed afterwards.
    SDSPIFull::Options opt = SDSPIFull::defaults();                           // Options with CRC16 checking on, () → Options
    opt.now_ms = now_ms;
    SDSPIFull sd(spi, opt);                                                   // Create SD driver and init the card, (spi, options) → SDSPIFull
    if (sd.status() != BlockError::Ok) {
        PF("no SD card (error %d)\n", static_cast<int>(sd.status()));
        while (true) DELAY_MS(1000);
    }
    BlockInfo info = sd.info();                                               // Geometry and capabilities, () → BlockInfo
    PF("card %s, %lu MiB\n", info.name, static_cast<unsigned long>(info.block_count / 2048));

    // --- Log samples to a raw block range (filesystem-free data logger) ---
    // Each 512-byte block holds a header (sequence number, tick) and 126 samples.
    // The last blocks of the card are used and restored afterwards, so a card that
    // carries a filesystem keeps its data (only the tail of the volume is touched).
    static uint8_t saved[DEMO_BLOCKS * 512], blockbuf[512], readback[512];
    uint32_t first = sd.block_count() - DEMO_BLOCKS;
    sd.read(first, saved, DEMO_BLOCKS);                                       // Read blocks, (block, buf, count) → BlockError
    uint32_t t0 = now_ms();
    for (uint32_t seq = 0; seq < DEMO_BLOCKS; seq++) {
        uint32_t words[128];
        words[0] = seq;
        words[1] = now_ms();
        for (int i = 2; i < 128; i++) words[i] = seq * 1000 + static_cast<uint32_t>(i);
        memcpy(blockbuf, words, sizeof(words));
        sd.write(first + seq, blockbuf, 1);                                   // Write blocks, (block, data, count) → BlockError
    }
    sd.sync();                                                                // Wait until programming is done, () → BlockError
    uint32_t t1 = now_ms();
    PF("wrote %d blocks in %lu ms\n", DEMO_BLOCKS, static_cast<unsigned long>(t1 - t0));

    // --- Verify by reading back ---
    // Read every block back and recompute the expected sample values; any single
    // mismatch (or a CRC16 error reported by the driver) is counted.
    uint32_t bad = 0;
    for (uint32_t seq = 0; seq < DEMO_BLOCKS; seq++) {
        if (sd.read(first + seq, readback, 1) != BlockError::Ok) { bad++; continue; }  // Read blocks, (block, buf, count) → BlockError
        uint32_t words[128];
        memcpy(words, readback, sizeof(words));
        if (words[0] != seq) bad++;
        for (int i = 2; i < 128; i++) if (words[i] != seq * 1000 + static_cast<uint32_t>(i)) { bad++; break; }
    }
    PF("verify: %lu bad blocks\n", static_cast<unsigned long>(bad));

    // --- Restore the original contents ---
    sd.write(first, saved, DEMO_BLOCKS);
    sd.sync();
    PF("restored\n");
    while (true) DELAY_MS(1000);
}

int main(void) {
    k_msleep(2000);
    const struct device *dev = DEVICE_DT_GET(SDSPI_SPI_NODE);
    struct spi_config cfg = {
        .frequency = 400000,                     // SD init needs <= 400 kHz; the driver raises it afterwards
        .operation = SPI_WORD_SET(8) | SPI_TRANSFER_MSB | SPI_OP_MODE_MASTER,
        .slave     = 0,
        .cs        = { .gpio = SDSPI_CS_GPIOS, .delay = 0 },
    };
    SPIConnectionZephyr connection(dev, cfg);                                 // Create SPI connection, (dev, cfg) → SPIConnectionZephyr
    app(connection);
    return 0;
}
