// SSD1331 demo example: Waveshare 0.95inch RGB OLED, Zephyr (rpi_pico2, SPI0 + display-dc/res aliases from the board overlay)
// Wiring: see cpp/boards/zephyr/rpi_pico2_rp2350a_m33.overlay (BUSY unused).
// Demo: a colour test card — eight colour bars over a gray ramp, a frame, a marker drawn with the controller's own rectangle command, then a slow scroll.
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

static void run(SSD1331Full<DisplayConnectionZephyr>& oled) {
    typedef SSD1331Full<DisplayConnectionZephyr> Chip;
    // --- Draw the test card into the framebuffer ---
    // Eight 12-pixel colour bars over a 32-row gray ramp show that all channels and the
    // 5-6-5 depth work; the one-pixel frame shows that the whole 96 x 64 area is addressed.
    static const uint16_t bars[8] = {
        Chip::WHITE, Chip::YELLOW, Chip::CYAN, Chip::GREEN, Chip::MAGENTA, Chip::RED, Chip::BLUE, Chip::BLACK
    };
    oled.clear(Chip::BLACK);                        // Fill framebuffer, (color=BLACK)
    for (int16_t x = 0; x < 96; x++) {
        oled.draw_rect(x, 2, 1, 30, bars[x / 12], true);  // Software rectangle, (x, y, w, h, color, fill=false)
        uint8_t g = static_cast<uint8_t>(x * 255 / 95);
        oled.draw_rect(x, 34, 1, 26, Chip::rgb(g, g, g), true);  // Software rectangle, (x, y, w, h, color, fill=false)
    }
    oled.draw_rect(0, 0, 96, 64, Chip::WHITE);      // Software rectangle, (x, y, w, h, color, fill=false)

    // --- Send it once ---
    oled.show();                                    // Send framebuffer, () → None
    DELAY_MS(3000);

    // --- Animate with hardware drawing ---
    // The chip draws the marker straight into its RAM: no framebuffer transfer per step.
    for (int step = 0; step < 60; step++) {
        int x = 4 + (step * 3) % 80;
        oled.show();                                // Send framebuffer, () → None
        oled.hw_rect(static_cast<uint8_t>(x), 36, 10, 10, Chip::WHITE, Chip::RED, true);  // Hardware rectangle, (x, y, w, h, line_color, fill_color=0, fill=false)
        DELAY_MS(50);
    }

    // --- Scroll the test card ---
    // The chip scrolls its own RAM, one column every 6 frames.
    oled.show();                                    // Send framebuffer, () → None
    oled.scroll(1, 0, 64, 0, 0);                    // Scroll, (horizontal, start_row, rows, vertical, interval 0..3)
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
    SSD1331Full<DisplayConnectionZephyr> oled(connection, frameBuffer);  // Create SSD1331 driver, (connection, buffer)
    if (!oled.valid()) {
        PRINT("SSD1331 init failed (check wiring)\n");
        return 1;
    }
    run(oled);
    return 0;
}
