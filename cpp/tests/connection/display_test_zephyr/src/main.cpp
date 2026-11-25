#include <zephyr/kernel.h>
#include <zephyr/sys/printk.h>
#include <zephyr/drivers/spi.h>
#include "DisplayConnectionZephyr.h"
#include "OutputPinZephyr.h"
#include "InputPinZephyr.h"
#include "display_smoke.h"

static int passed = 0;
static int failed = 0;

static void report(const char* label, bool ok) {
    if (ok) { printk("PASS %s\n", label); passed++; }
    else    { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device* spi_dev = DEVICE_DT_GET(DT_NODELABEL(spi0));

    // CS# is driven by the SPI driver from the spi0 node's cs-gpios.
    struct spi_config spi_cfg = {};
    spi_cfg.frequency = 4000000;
    spi_cfg.operation = SPI_WORD_SET(8) | SPI_TRANSFER_MSB | SPI_OP_MODE_MASTER;
    spi_cfg.cs.gpio   = GPIO_DT_SPEC_GET(DT_NODELABEL(spi0), cs_gpios);
    spi_cfg.cs.delay  = 0;

    const struct gpio_dt_spec dcSpec   = GPIO_DT_SPEC_GET(DT_ALIAS(display_dc), gpios);
    const struct gpio_dt_spec resSpec  = GPIO_DT_SPEC_GET(DT_ALIAS(display_res), gpios);
    const struct gpio_dt_spec busySpec = GPIO_DT_SPEC_GET(DT_ALIAS(display_busy), gpios);

    OutputPinZephyr dc(dcSpec);
    OutputPinZephyr res(resSpec);
    InputPinZephyr  busy(busySpec);

    DisplayConnectionZephyr connection(spi_dev, spi_cfg, nullptr, &dc, &res, &busy);
    displaySmoke(connection, false, report);

    // A full-duplex config contradicts kHalfDuplexHW: the wire mode must be rejected.
    DisplayConnectionZephyr mismatch(spi_dev, spi_cfg, nullptr, &dc, &res, &busy,
                                     DisplayWireMode::kHalfDuplexHW);
    report("half_duplex_hw_needs_spi_half_duplex_flag", !mismatch.valid());

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
