#include <zephyr/kernel.h>
#include <string.h>
#include <math.h>

#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "SPIConnectionZephyr.h"
#include "23X1024.h"

#ifndef SRAM23X1024_SPI_NODE
#define SRAM23X1024_SPI_NODE DT_NODELABEL(spi0)
#endif
#ifndef SRAM23X1024_CS_GPIOS
#define SRAM23X1024_CS_GPIOS GPIO_DT_SPEC_GET_BY_IDX(SRAM23X1024_SPI_NODE, cs_gpios, 0)
#endif

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

static uint32_t now_ms() { return k_uptime_get_32(); }

int main(void) {
    const struct device *dev = DEVICE_DT_GET(SRAM23X1024_SPI_NODE);
    struct spi_config cfg = {
        .frequency = 8000000,
        .operation = SPI_WORD_SET(8) | SPI_TRANSFER_MSB | SPI_OP_MODE_MASTER,
        .slave     = 0,
        .cs        = { .gpio = SRAM23X1024_CS_GPIOS, .delay = 0 },
    };
    SPIConnectionZephyr connection(dev, cfg);                              // Create SPI connection, (dev, cfg) → SPIConnectionZephyr
    SRAM23X1024Full sram(connection);                                        // Create 23X1024 Full driver, (connection) → SRAM23X1024Full

    // --- Store a 256-entry lookup table in external SRAM ---
    // A sine table is the kind of data a microcontroller with little RAM
    // keeps outside: written once, then read back entry by entry.
    uint8_t table[256];
    for (int i = 0; i < 256; i++) table[i] = (uint8_t)(127.5f + 127.5f * sinf(2.0f * 3.14159265f * i / 256.0f));
    sram.write(0x00000, table, sizeof(table));                               // Block write, (address, data, length)
    uint8_t check[256];
    sram.read(0x00000, check, sizeof(check));                                // Block read, (address, buf, length)
    printk("sine table: %s (sin(64/256 turn) = %u)\n",
           memcmp(table, check, sizeof(table)) == 0 ? "ok" : "MISMATCH", (unsigned)sram.read_byte(64));   // Read one byte, (address) → byte

    // --- Wipe the whole array and spot-check it ---
    // Zero write time means the 128 KiB fill runs at SPI speed with no
    // waiting; the first, middle and last byte prove the full range.
    sram.fill(0x00000, sram.size(), 0xA5);                                   // Fill a range, (address, length, value=0)
    printk("fill spot check: 0x%02X 0x%02X 0x%02X\n",
           sram.read_byte(0x00000), sram.read_byte(0x10000), sram.read_byte(0x1FFFF));                      // Read one byte, (address) → byte

    // --- Page mode: a write that crosses a 32-byte page boundary ---
    // The hardware would wrap inside the page; the driver splits the
    // transfer so the 64 bytes starting at address 16 still read back in order.
    sram.set_mode(SRAM23X1024Full::MODE_PAGE);                               // Select operating mode, (mode=MODE_PAGE=0x80)
    uint8_t ramp[64];
    for (int i = 0; i < 64; i++) ramp[i] = (uint8_t)(0x40 + i);
    sram.write(16, ramp, sizeof(ramp));                                      // Block write, (address, data, length)
    uint8_t ramp_back[64];
    sram.read(16, ramp_back, sizeof(ramp_back));                             // Block read, (address, buf, length)
    printk("page-crossing write: %s\n", memcmp(ramp, ramp_back, sizeof(ramp)) == 0 ? "ok" : "MISMATCH");
    sram.set_mode(SRAM23X1024Full::MODE_SEQUENTIAL);                         // Select operating mode, (mode=MODE_SEQUENTIAL=0x40)

    // --- Measure throughput for a 4 KiB transfer ---
    // Sequential mode streams the block in 256-byte transactions; now_ms()
    // times them in milliseconds.
    static uint8_t block[4096];
    for (int i = 0; i < 4096; i++) block[i] = (uint8_t)i;
    uint32_t t0 = now_ms();
    sram.write(0x01000, block, sizeof(block));                               // Block write, (address, data, length)
    uint32_t t1 = now_ms();
    sram.read(0x01000, block, sizeof(block));                                // Block read, (address, buf, length)
    uint32_t t2 = now_ms();
    printk("4 KiB write: %lu ms, read: %lu ms\n", (unsigned long)(t1 - t0), (unsigned long)(t2 - t1));
    for (;;) k_msleep(1000);
}
