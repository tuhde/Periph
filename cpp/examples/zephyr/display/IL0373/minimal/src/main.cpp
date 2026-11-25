// IL0373 minimal example: Waveshare 2.13inch e-Paper (D), Zephyr (rpi_pico2, SPI0 + display-dc/res/busy aliases from the board overlay).
// Wiring: see cpp/boards/zephyr/rpi_pico2_rp2350a_m33.overlay
// Each refresh blocks until BUSY goes high (the panel datasheet gives no update time).
#include <zephyr/kernel.h>
#include <zephyr/sys/printk.h>
#include <zephyr/drivers/spi.h>
#include "DisplayConnectionZephyr.h"
#include "OutputPinZephyr.h"
#include "InputPinZephyr.h"
#include "IL0373.h"

#define PRINT printk
#define NOW_MS() ((uint32_t)k_uptime_get())

static uint8_t frameBuffer[2756];

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

    OutputPinZephyr dc(dcSpec);                       // Create D/C# pin, (gpio_dt_spec)
    OutputPinZephyr res(resSpec);                     // Create RES# pin, (gpio_dt_spec)
    InputPinZephyr  busy(busySpec);                   // Create BUSY pin, (gpio_dt_spec)

    DisplayConnectionZephyr connection(spi_dev, spi_cfg, nullptr, &dc, &res, &busy);   // Create display connection, (spi_dev, spi_cfg, cs=nullptr, dc, res, busy, wireMode=kSimplex)
    IL0373Minimal<DisplayConnectionZephyr> epd(connection, frameBuffer);   // Create IL0373 driver, (connection, buffer, width=104, height=212, busy_timeout_ms=30000)
    if (!epd.valid()) {
        PRINT("IL0373 init failed: BUSY never went high\n");
        return 1;
    }
    typedef IL0373Minimal<DisplayConnectionZephyr> Epd;
    epd.clear(Epd::WHITE);                                                             // Fill framebuffer, (color=WHITE)
    epd.set_pixel(10, 10, Epd::BLACK);                                                 // Set pixel, (x, y, color=BLACK)
    for (int16_t x = 20; x < 100; x++) {
        epd.set_pixel(x, 40, Epd::BLACK);                                                // Set pixel, (x, y, color=BLACK)
    }
    epd.show();                                                                        // Update display, () → bool
    epd.sleep();                                                                       // Enter deep sleep, () → bool
    return 0;
}
