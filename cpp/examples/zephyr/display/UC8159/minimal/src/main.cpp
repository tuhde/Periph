// UC8159 minimal example: Waveshare 5.65inch e-Paper (F), Zephyr (rpi_pico2, SPI0 + display-dc/res/busy aliases from the board overlay).
// Wiring: see cpp/boards/zephyr/rpi_pico2_rp2350a_m33.overlay
// Each refresh blocks for about 30 s.
#include <zephyr/kernel.h>
#include <zephyr/sys/printk.h>
#include <zephyr/drivers/spi.h>
#include "DisplayConnectionZephyr.h"
#include "OutputPinZephyr.h"
#include "InputPinZephyr.h"
#include "UC8159.h"

#define PRINT printk
#define NOW_MS() ((uint32_t)k_uptime_get())

static uint8_t frameBuffer[100801];

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
    UC8159Minimal<DisplayConnectionZephyr> epd(connection, frameBuffer, sizeof(frameBuffer));   // Create UC8159 driver, (connection, buffer, buffer_len, busy_timeout_ms=60000)
    if (!epd.valid()) {
        PRINT("UC8159 init failed: BUSY never went high\n");
        return 1;
    }
    typedef UC8159Minimal<DisplayConnectionZephyr> Epd;
    epd.clear(Epd::WHITE);                                                             // Fill framebuffer, (color=WHITE)
    for (int16_t x = 0; x < 600; x++) {
        uint8_t colour = static_cast<uint8_t>((x * 7) / 600);                         // seven vertical bars: black .. orange
        for (int16_t y = 0; y < 448; y++) {
            epd.set_pixel(x, y, colour);                                               // Set one pixel, (x, y, color=BLACK)
        }
    }
    PRINT("refreshing, about 30 s ...\n");
    bool ok = epd.show();                                                              // Update display (about 30 s), () → bool
    PRINT(ok ? "done\n" : "BUSY timeout during refresh\n");
    epd.sleep();                                                                       // Enter deep sleep, () → bool
    return 0;
}
