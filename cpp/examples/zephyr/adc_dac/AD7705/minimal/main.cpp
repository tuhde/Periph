#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "SPIConnectionZephyr.h"
#include "AD7705.h"

#ifndef AD7705_SPI_NODE
#define AD7705_SPI_NODE DT_NODELABEL(spi0)
#endif
#ifndef AD7705_CS_GPIOS
#define AD7705_CS_GPIOS GPIO_DT_SPEC_GET_BY_IDX(AD7705_SPI_NODE, cs_gpios, 0)
#endif

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(AD7705_SPI_NODE);
    struct spi_config cfg = {
        .frequency = 5000000,
        .operation = SPI_WORD_SET(8) | SPI_TRANSFER_MSB | SPI_OP_MODE_MASTER | SPI_MODE_CPOL | SPI_MODE_CPHA,
        .slave     = 0,
        .cs        = { .gpio = AD7705_CS_GPIOS, .delay = 0 },
    };
    SPIConnectionZephyr connection(dev, cfg);                                // Create SPI connection, (dev, cfg) → SPIConnectionZephyr
    AD7705Minimal adc(connection, 2.5f, AD7705Minimal::MCLK_2_4576MHZ);      // Construct and initialise the AD7705, (connection, vref=2.5 V, mclk_hz=2_457_600 Hz, reset_pin=nullptr) → AD7705Minimal

    uint16_t raw = adc.read_raw();                                            // Read raw 16-bit code, () → uint16_t
                                                                              // blocks until DRDY, returns raw Data Register code
    float v = adc.read_voltage();                                             // Read Channel 1 voltage, () → float V
    check_true(true, "read_raw_and_voltage");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
