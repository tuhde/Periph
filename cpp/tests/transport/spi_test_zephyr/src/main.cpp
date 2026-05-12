#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <zephyr/drivers/gpio.h>
#include "SPITransportZephyr.h"

#ifndef SPI_TEST_NODE
#define SPI_TEST_NODE DT_NODELABEL(spi0)
#endif

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *spi_dev = DEVICE_DT_GET(SPI_TEST_NODE);

    struct spi_config cfg = {
        .frequency = 1000000,
        .operation = SPI_WORD_SET(8) | SPI_TRANSFER_MSB | SPI_OP_MODE_MASTER,
        .slave     = 0,
        .cs        = { .gpio = GPIO_DT_SPEC_GET(SPI_TEST_NODE, cs_gpios), .delay = 0 },
    };

    SPITransportZephyr transport(spi_dev, cfg);

    const uint8_t cmd[1] = {0x00};
    transport.write(cmd, 1);
    check_true(true, "write accepted");

    uint8_t buf[1] = {0};
    transport.read(buf, 1);
    check_true(true, "read returns data");

    transport.write_read(cmd, 1, buf, 1);
    check_true(true, "write_read returns data");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
