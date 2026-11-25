// IL0373 demo example: Waveshare 2.13inch e-Paper (D), Zephyr (rpi_pico2, SPI0 + display-dc/res/busy aliases from the board overlay).
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
    IL0373Full<DisplayConnectionZephyr> epd(connection, frameBuffer);   // Create IL0373 driver, (connection, buffer, width=104, height=212, busy_timeout_ms=30000)
    if (!epd.valid()) {
        PRINT("IL0373 init failed: BUSY never went high\n");
        return 1;
    }
    typedef IL0373Full<DisplayConnectionZephyr> Epd;

    // --- Compose the status card in the framebuffer ---
    // White page, a one-pixel black border inset by 4 px (a frame on the very edge is
    // hidden by the panel bezel) and a black header bar across the top 24 rows.
    epd.clear(Epd::WHITE);                                                             // Fill framebuffer, (color=WHITE)
    epd.draw_rect(2, 2, 100, 208, Epd::BLACK);                                         // Draw rectangle, (x, y, w, h, color=BLACK, fill=false)
    epd.draw_rect(3, 3, 98, 24, Epd::BLACK, true);                                      // Draw rectangle, (x, y, w, h, color=BLACK, fill=false)

    // --- Bar chart of eight readings ---
    // Axes first, then one filled bar per value; the tallest bar is filled and the others are outlines.
    static const int16_t values[8] = {30, 55, 80, 45, 120, 90, 60, 100};
    int16_t peak = 0;
    for (int i = 0; i < 8; i++) {
        if (values[i] > peak) peak = values[i];
    }
    epd.draw_line(10, 190, 95, 190, Epd::BLACK);                                      // Draw line, (x0, y0, x1, y1, color=BLACK)
    epd.draw_line(10, 60, 10, 190, Epd::BLACK);                                        // Draw line, (x0, y0, x1, y1, color=BLACK)
    for (int i = 0; i < 8; i++) {
        epd.draw_rect(14 + i * 10, 190 - values[i], 8, values[i], Epd::BLACK, values[i] == peak); // Draw rectangle, (x, y, w, h, color=BLACK, fill=false)
    }

    // --- Refresh and time it ---
    // show() blocks for the whole DTM1/DTM2/DRF/POF sequence.
    uint32_t t0 = NOW_MS();
    bool ok = epd.show();                                                              // Update display, () → bool
    uint32_t elapsed = NOW_MS() - t0;
    PRINT("%s after %lu ms\n", ok ? "refresh took" : "BUSY timeout", (unsigned long)elapsed);

    // --- Save the image and power down ---
    // The panel keeps the picture without power; deep sleep stops the controller drawing current.
    epd.sleep();                                                                       // Enter deep sleep, () → bool
    return 0;
}
