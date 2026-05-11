#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <zephyr/drivers/spi.h>
#include "SPITransportZephyr.h"

#ifndef TEST_SPI_NODE
#define TEST_SPI_NODE DT_NODELABEL(spi0)
#endif

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) { printk("PASS %s\n", label); passed++; }
    else           { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device* dev = DEVICE_DT_GET(TEST_SPI_NODE);

    struct spi_config cfg = {
        .frequency = 1_000_000,
        .operation = SPI_WORD_SET(8) | SPI_TRANSFER_MSB | SPI_OP_MODE_MASTER,
        .slave = 0,
        .cs = { .gpio = GPIO_DT_SPEC_GET(DT_NODELABEL(spi0), cs_gpios), .delay = 0 },
    };

    SPITransportZephyr transport(dev, &cfg);

    uint8_t tx_data[] = {0x01, 0x02, 0x03};
    uint8_t rx_buf[3] = {0};

    transport.write(tx_data, sizeof(tx_data));
    check_true("write completed", true);

    transport.read(rx_buf, sizeof(rx_buf));
    check_true("read completed", true);

    uint8_t cmd[] = {0x55, 0xAA};
    uint8_t resp[2] = {0};
    transport.write_read(cmd, sizeof(cmd), resp, sizeof(resp));
    check_true("write_read completed", true);

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}