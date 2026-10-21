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

static void print_hex(const uint8_t* p, int n) {
    for (int i = 0; i < n; i++) PF("%02X ", p[i]);
    PF("\n");
}

static void app(SPIExtensions& spi) {
    SDSPIMinimal::Options opt;                                                // Driver options, (speed_hz=12000000, now_ms=nullptr, crc=false)
    opt.now_ms = now_ms;
    SDSPIMinimal sd(spi, opt);                                                // Create SD driver and init the card, (spi, options) → SDSPIMinimal

    while (sd.status() != BlockError::Ok) {
        PF("no SD card (error %d), retrying\n", static_cast<int>(sd.status()));
        DELAY_MS(2000);
        sd.begin();                                                           // Re-run card init, () → BlockError
    }

    PF("capacity: %lu blocks = %lu MiB\n",
       static_cast<unsigned long>(sd.block_count()),                          // Capacity in 512-byte blocks, () → uint32 blocks
       static_cast<unsigned long>(sd.block_count() / 2048));
    static uint8_t block[512];
    while (true) {
        BlockError e = sd.read_block(0, block);                               // Read one 512-byte block, (block, buf) → BlockError
        PF("block 0 (%d): ", static_cast<int>(e));
        print_hex(block, 16);
        PF("boot signature: %02X%02X\n", block[510], block[511]);
        DELAY_MS(2000);
    }
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
