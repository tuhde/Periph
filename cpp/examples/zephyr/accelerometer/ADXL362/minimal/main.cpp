#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "SPIConnectionZephyr.h"
#include "ADXL362.h"

#ifndef ADXL362_SPI_NODE
#define ADXL362_SPI_NODE DT_NODELABEL(spi0)
#endif
#ifndef ADXL362_CS_GPIOS
#define ADXL362_CS_GPIOS DT_PROP(ADXL362_SPI_NODE, cs_gpios)
#endif

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(ADXL362_SPI_NODE);
    struct spi_config cfg = {
        .frequency = 8000000,
        .operation = SPI_WORD_SET(8) | SPI_TRANSFER_MSB | SPI_OP_MODE_MASTER,
        .slave     = 0,
        .cs        = { .gpio = ADXL362_CS_GPIOS, .delay = 0 },
    };
    SPIConnectionZephyr connection(dev, cfg);                              // Create SPI connection, (dev, cfg) → SPIConnectionZephyr
    ADXL362Minimal accel(connection);                                       // Create ADXL362 driver, (connection) → ADXL362Minimal

    for (;;) {
        float x, y, z;
        accel.read(x, y, z);                                                // Read 3-axis acceleration, (x, y, z) → g, g, g
        printk("x=%+.3f  y=%+.3f  z=%+.3f g\n", x, y, z);
        k_msleep(100);
    }
}