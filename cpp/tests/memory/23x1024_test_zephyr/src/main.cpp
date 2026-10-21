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

int main(void) {
    const struct device *dev = DEVICE_DT_GET(SRAM23X1024_SPI_NODE);
    struct spi_config cfg = {
        .frequency = 8000000,
        .operation = SPI_WORD_SET(8) | SPI_TRANSFER_MSB | SPI_OP_MODE_MASTER,
        .slave     = 0,
        .cs        = { .gpio = SRAM23X1024_CS_GPIOS, .delay = 0 },
    };
    SPIConnectionZephyr connection(dev, cfg);                              // Create SPI connection, (dev, cfg) → SPIConnectionZephyr
    SRAM23X1024Full sram(connection);                                          // aborts if the MODE register round-trip fails
    check_true(true, "init_mode_roundtrip");
    check_true(sram.size() == 131072, "size");
    check_true(sram.get_mode() == SRAM23X1024Full::MODE_SEQUENTIAL, "default_mode_sequential");

    sram.write_byte(0x00000, 0xA5);
    check_true(sram.read_byte(0x00000) == 0xA5, "byte_roundtrip_first");
    sram.write_byte(0x1FFFF, 0x5A);
    check_true(sram.read_byte(0x1FFFF) == 0x5A, "byte_roundtrip_last");
    sram.write_byte(0x10000, 0x3C);
    check_true(sram.read_byte(0x10000) == 0x3C && sram.read_byte(0x00000) == 0xA5, "address_bit16_distinct");

    static uint8_t pattern[1000], back[1000];
    for (int i = 0; i < 1000; i++) pattern[i] = (uint8_t)(i * 7 + 3);
    sram.write(0x00400, pattern, sizeof(pattern));
    sram.read(0x00400, back, sizeof(back));
    check_true(memcmp(pattern, back, sizeof(pattern)) == 0, "block_roundtrip_chunked");

    sram.fill(0x02000, 100, 0xC3);
    bool fill_ok = true;
    for (int i = 0; i < 100; i++) fill_ok = fill_ok && sram.read_byte(0x02000 + i) == 0xC3;
    check_true(fill_ok && sram.read_byte(0x02000 + 100) != 0xC3, "fill_range");

    sram.write(0x1FFF0, pattern, 16);
    sram.read(0x1FFF0, back, 16);
    check_true(memcmp(pattern, back, 16) == 0, "block_at_array_end");

    sram.set_mode(SRAM23X1024Full::MODE_PAGE);
    check_true(sram.get_mode() == SRAM23X1024Full::MODE_PAGE, "set_mode_page");
    sram.write(0x04010, pattern, 64);          // crosses the pages at 0x4020 and 0x4040
    sram.read(0x04010, back, 64);
    check_true(memcmp(pattern, back, 64) == 0, "page_mode_crossing_linear");

    sram.set_mode(SRAM23X1024Full::MODE_BYTE);
    check_true(sram.get_mode() == SRAM23X1024Full::MODE_BYTE, "set_mode_byte");
    sram.write(0x05000, pattern, 8);
    sram.read(0x05000, back, 8);
    check_true(memcmp(pattern, back, 8) == 0, "byte_mode_block");

    sram.set_mode(SRAM23X1024Full::MODE_SEQUENTIAL);
    check_true(sram.get_mode() == SRAM23X1024Full::MODE_SEQUENTIAL, "set_mode_sequential");

    sram.reset_io();
    check_true(sram.get_mode() == SRAM23X1024Full::MODE_SEQUENTIAL, "reset_io_stays_spi");
    check_true(sram.read_byte(0x00000) == 0xA5, "data_survives_reset_io");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
