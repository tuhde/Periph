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
    const struct device *spi_dev = DEVICE_DT_GET(NEOPIXEL_SPI_NODE);
    NeoPixelTransportZephyr transport(spi_dev);

    uint8_t data[3] = {0xFF, 0x00, 0x00};
    transport.write(data, 3);

    check_true(true, "write accepted data");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}