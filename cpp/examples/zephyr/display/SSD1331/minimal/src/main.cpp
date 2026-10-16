// SSD1331 minimal example: Waveshare 0.95inch RGB OLED, Zephyr (rpi_pico2, SPI0 + display-dc/res aliases from the board overlay)
// Wiring: see cpp/boards/zephyr/rpi_pico2_rp2350a_m33.overlay (BUSY unused).
// Minimal use: eight colour bars and a white frame, drawn into the framebuffer and sent once.
#include <zephyr/kernel.h>
#include <zephyr/sys/printk.h>
#include <zephyr/drivers/spi.h>
#include "DisplayConnectionZephyr.h"
#include "OutputPinZephyr.h"
#include "InputPinZephyr.h"
#include "SSD1331.h"

#define DELAY_MS(ms) k_msleep(ms)
#define PRINT printk
#define NOW_MS() ((uint32_t)k_uptime_get())

static uint8_t frameBuffer[96 * 64 * 2];

static void run(SSD1331Minimal<DisplayConnectionZephyr>& oled) {
    static const uint16_t bars[8] = {
        oled.WHITE, oled.YELLOW, oled.CYAN, oled.GREEN, oled.MAGENTA, oled.RED, oled.BLUE, oled.BLACK
    };
    for (int16_t y = 0; y < oled.height(); y++) {
        for (int16_t x = 0; x < oled.width(); x++) {
            oled.set_pixel(x, y, bars[x / 12]);     // Set pixel, (x, y, color=WHITE)
        }
    }
    for (int16_t x = 0; x < oled.width(); x++) {
        oled.set_pixel(x, 0, oled.WHITE);           // Set pixel, (x, y, color=WHITE)
        oled.set_pixel(x, oled.height() - 1, oled.WHITE);
    }

    oled.show();                                    // Send framebuffer, () → None
    PRINT("done\n");
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

    OutputPinZephyr dc(dcSpec);                       // Create D/C# pin, (gpio_dt_spec)
    OutputPinZephyr res(resSpec);                     // Create RES# pin, (gpio_dt_spec)

    DisplayConnectionZephyr connection(spi_dev, spi_cfg, nullptr, &dc, &res, nullptr);   // Create display connection, (spi_dev, spi_cfg, cs=nullptr, dc, res, busy, wireMode=kSimplex)
    SSD1331Minimal<DisplayConnectionZephyr> oled(connection, frameBuffer);  // Create SSD1331 driver, (connection, buffer)
    if (!oled.valid()) {
        PRINT("SSD1331 init failed (check wiring)\n");
        return 1;
    }
    run(oled);
    return 0;
}
