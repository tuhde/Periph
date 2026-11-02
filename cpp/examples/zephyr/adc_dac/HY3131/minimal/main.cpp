#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "SPIConnectionZephyr.h"
#include "HY3131.h"

#ifndef HY3131_SPI_NODE
#define HY3131_SPI_NODE DT_NODELABEL(spi0)
#endif
#ifndef HY3131_CS_GPIOS
#define HY3131_CS_GPIOS GPIO_DT_SPEC_GET_BY_IDX(HY3131_SPI_NODE, cs_gpios, 0)
#endif

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(HY3131_SPI_NODE);
    struct spi_config cfg = {
        .frequency = 1000000,
        .operation = SPI_WORD_SET(8) | SPI_TRANSFER_MSB | SPI_OP_MODE_MASTER,
        .slave     = 0,
        .cs        = { .gpio = HY3131_CS_GPIOS, .delay = 0 },
    };
    SPIConnectionZephyr connection(dev, cfg);                                // Create SPI connection, (dev, cfg) → SPIConnectionZephyr
    HY3131Minimal adc(connection, 1.2f);                                    // Construct and initialise the HY3131, (connection, vref=1.2 V) → HY3131Minimal
    float v = adc.read_voltage();                                           // Read AD1 differential voltage, () → float V
    check_true(v == v, "read_voltage");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
