#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <zephyr/drivers/gpio.h>
#include "SiPoTransportZephyr.h"

#ifndef SIPO_TEST_NODE
#define SIPO_TEST_NODE DT_NODELABEL(spi0)
#endif
#ifndef SIPO_RCK_NODE
#define SIPO_RCK_NODE   DT_ALIAS(sipo_rck)
#endif
#ifndef SIPO_SRCLR_NODE
#define SIPO_SRCLR_NODE DT_ALIAS(sipo_srclr)
#endif
#ifndef SIPO_G_NODE
#define SIPO_G_NODE     DT_ALIAS(sipo_g)
#endif

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *spi_dev = DEVICE_DT_GET(SIPO_TEST_NODE);

    struct spi_config cfg = {
        .frequency = 1000000,
        .operation = SPI_WORD_SET(8) | SPI_TRANSFER_MSB | SPI_OP_MODE_MASTER,
        .slave     = 0,
    };

    static const struct gpio_dt_spec rck   = GPIO_DT_SPEC_GET(SIPO_RCK_NODE,   gpios);
    static const struct gpio_dt_spec srclr = GPIO_DT_SPEC_GET(SIPO_SRCLR_NODE, gpios);
    static const struct gpio_dt_spec g     = GPIO_DT_SPEC_GET(SIPO_G_NODE,     gpios);

    SiPoTransportZephyr transport(spi_dev, cfg, rck, srclr, g);

    const uint8_t data1[1] = {0xA5};
    transport.write(data1, 1);
    check_true(true, "write accepted");

    const uint8_t data2[2] = {0x00, 0xFF};
    transport.write(data2, 2);
    check_true(true, "write multi-byte accepted");

    check_true(transport.clear() == 0, "clear returns 0 when configured");

    check_true(transport.set_output_enable(false) == 0,
               "set_output_enable(false) returns 0 when configured");
    check_true(transport.set_output_enable(true) == 0,
               "set_output_enable(true) returns 0 when configured");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
