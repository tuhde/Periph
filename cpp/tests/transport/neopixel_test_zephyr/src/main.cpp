#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "NeoPixelTransportZephyr.h"

#ifndef NEOPIXEL_SPI_NODE
#define NEOPIXEL_SPI_NODE DT_NODELABEL(spi0)
#endif

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device* spi_dev = DEVICE_DT_GET(NEOPIXEL_SPI_NODE);
    if (!spi_dev) {
        printk("FAIL spi_dev_null\n");
        return 1;
    }

    struct spi_config config = {};
    config.frequency = 2400000;
    config.operation = SPI_WORD_SET(8) | SPI_TRANSFER_MSB | SPI_OP_MODE_MASTER;

    NeopixelTransportZephyr transport(spi_dev, config);

    transport.write((const uint8_t*)"\x00\x00\x00", 3);
    check_true("write_black_no_error", true);

    transport.write((const uint8_t*)"\xFF\xFF\xFF", 3);
    check_true("write_white_no_error", true);

    transport.write((const uint8_t*)"\x00\xFF\x00", 3);
    check_true("write_green_no_error", true);

    transport.write((const uint8_t*)"\x10\x20\x30\x40", 4);
    check_true("write_4bytes_no_error", true);

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}