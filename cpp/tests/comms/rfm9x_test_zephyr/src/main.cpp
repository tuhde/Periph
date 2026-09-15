#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <zephyr/sys/printk.h>
#include "SPIConnectionZephyr.h"
#include "RFM9x.h"

#ifndef RFM9X_SPI_NODE
#define RFM9X_SPI_NODE DT_NODELABEL(spi0)
#endif
#ifndef RFM9X_CS_GPIOS
#define RFM9X_CS_GPIOS DT_PROP(RFM9X_SPI_NODE, cs_gpios)
#endif

static int passed = 0, failed = 0;

static void check_eq(const char* label, uint8_t got, uint8_t expected) {
    if (got == expected) { printk("PASS %s\n", label); passed++; }
    else { printk("FAIL %s: got 0x%02X, expected 0x%02X\n", label, got, expected); failed++; }
}

static void check_true(const char* label, bool condition) {
    if (condition) { printk("PASS %s\n", label); passed++; }
    else           { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(RFM9X_SPI_NODE);
    struct spi_config cfg = {
        .frequency = 5000000,
        .operation = SPI_WORD_SET(8) | SPI_TRANSFER_MSB | SPI_OP_MODE_MASTER,
        .slave     = 0,
        .cs        = { .gpio = RFM9X_CS_GPIOS, .delay = 0 },
    };
    SPIConnectionZephyr connection(dev, cfg);
    RFM95Full radio(connection, 868000000);             // Create RFM95W full driver, (connection, frequency_hz=868e6) → RFM95Full

    check_eq("version", radio.version(), 0x12);

    radio.configure(7, 125.0, 5);
    check_true("configure", true);

    radio.standby();
    check_eq("standby_mode", radio._read_reg(0x01) & 0x07, 0x01);

    radio.send((const uint8_t*)"test", 4);
    check_eq("irq_tx_done_cleared", radio._read_reg(0x12) & 0x08, 0x00);

    radio.sleep();
    check_eq("sleep_mode", radio._read_reg(0x01) & 0x07, 0x00);

    radio.standby();
    check_true("wake", true);

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
