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
    SRAM23X1024Minimal sram(connection);                                     // Create 23X1024 driver, (connection) → SRAM23X1024Minimal

    uint8_t counter = 0;
    while (true) {
        sram.write_byte(0x00000, counter);                                   // Write one byte, (address 0–131071, value 0–255)
        uint8_t value = sram.read_byte(0x00000);                             // Read one byte, (address 0–131071) → byte
        printk("wrote 0x%02X, read 0x%02X\n", counter, value);
        counter++;
        k_msleep(500);
    }
}
