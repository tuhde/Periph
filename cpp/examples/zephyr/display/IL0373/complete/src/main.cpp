// IL0373 complete example: Waveshare 2.13inch e-Paper (D), Zephyr (rpi_pico2, SPI0 + display-dc/res/busy aliases from the board overlay).
// Wiring: see cpp/boards/zephyr/rpi_pico2_rp2350a_m33.overlay
// Each refresh blocks until BUSY goes high (the panel datasheet gives no update time). The read_* calls need DOUT: join DIN and DOUT through a ~1 kOhm resistor to MISO.
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
    spi_cfg.frequency = 1000000;
    spi_cfg.operation = SPI_WORD_SET(8) | SPI_TRANSFER_MSB | SPI_OP_MODE_MASTER;
    spi_cfg.cs.gpio   = GPIO_DT_SPEC_GET(DT_NODELABEL(spi0), cs_gpios);
    spi_cfg.cs.delay  = 0;

    const struct gpio_dt_spec dcSpec   = GPIO_DT_SPEC_GET(DT_ALIAS(display_dc), gpios);
    const struct gpio_dt_spec resSpec  = GPIO_DT_SPEC_GET(DT_ALIAS(display_res), gpios);
    const struct gpio_dt_spec busySpec = GPIO_DT_SPEC_GET(DT_ALIAS(display_busy), gpios);

    OutputPinZephyr dc(dcSpec);                       // Create D/C# pin, (gpio_dt_spec)
    OutputPinZephyr res(resSpec);                     // Create RES# pin, (gpio_dt_spec)
    InputPinZephyr  busy(busySpec);                   // Create BUSY pin, (gpio_dt_spec)

    DisplayConnectionZephyr connection(spi_dev, spi_cfg, nullptr, &dc, &res, &busy, DisplayWireMode::kHalfDuplexPin);   // Create display connection, (spi_dev, spi_cfg, cs=nullptr, dc, res, busy, wireMode=kSimplex)
    IL0373Full<DisplayConnectionZephyr> epd(connection, frameBuffer);   // Create IL0373 driver, (connection, buffer, width=104, height=212, busy_timeout_ms=30000)
    if (!epd.valid()) {
        PRINT("IL0373 init failed: BUSY never went high\n");
        return 1;
    }
    typedef IL0373Full<DisplayConnectionZephyr> Epd;
    epd.clear(Epd::WHITE);                                                             // Fill framebuffer, (color=WHITE)
                                                                                          // every pixel set to white
    epd.set_pixel(5, 5, Epd::BLACK);                                                   // Set pixel, (x, y, color=BLACK)
                                                                                          // clears the bit for that pixel
    epd.draw_rect(20, 20, 60, 40, Epd::BLACK, true);                                     // Draw rectangle, (x, y, w, h, color=BLACK, fill=false)
                                                                                          // filled black block
    epd.draw_line(0, 0, 103, 211, Epd::BLACK);                                         // Draw line, (x0, y0, x1, y1, color=BLACK)
                                                                                          // Bresenham diagonal
    static const uint8_t box[4] = {0xFF, 0x81, 0x81, 0xFF};
    epd.blit(70, 20, box, 8, 4);                                                      // Copy 1-bpp bitmap, (x, y, data, w, h, color=BLACK)
                                                                                          // 8 x 4 box, MSB first
    uint8_t colour = epd.get_pixel(30, 30);                                            // Read framebuffer pixel, (x, y) → color
                                                                                          // BLACK here
    epd.set_flip(false, false);                                                        // Mirror output, (horizontal, vertical)
                                                                                          // PSR.SHL / UD at power-on values
    epd.set_power(14.0f, 14.0f, 20.0f);                                        // Set source/gate power, (vdh V, vdl V, vgh=20 V)
                                                                                          // PWR: +-14 V sources, +-20 V gates (the power-on values)
    epd.set_frame_rate(50);                                                            // Set frame rate, (hz 4–200)
                                                                                          // nearest PLL M/N entry (M7 N4)
    epd.set_soft_start(0x17, 0x17, 0x17);                                              // Set booster soft start, (a, b, c)
                                                                                          // vendor BTST phases
    epd.set_interval(7);                                                               // Set VCOM/data interval, (cdi 0–15, border=2, polarity=1)
                                                                                          // keeps DDX = 01 so the framebuffer mapping holds
    epd.set_temperature_sensor(false, 0);                                              // Select temperature sensor, (external, offset -8..7 °C)
                                                                                          // internal sensor, no offset
    uint8_t status = 0;
    int8_t temperature = 0;
    uint8_t revision = 0;
    bool haveStatus = epd.read_status(&status);                                        // Read FLG, (status*) → bool
                                                                                          // needs DOUT wired (half-duplex)
    bool haveTemp = epd.read_temperature(&temperature);                                // Read sensor, (celsius*) → bool
                                                                                          // signed internal temperature
    bool haveRev = epd.read_revision(&revision);                                        // Read REV, (revision*) → bool
                                                                                          // LUT_REV, one byte
    epd.show();                                                                        // Update display, () → bool
                                                                                          // OLD/NEW planes, DRF, border floating, POF
    epd.draw_rect(16, 100, 64, 40, Epd::BLACK, true);                                  // Draw rectangle, (x, y, w, h, color=BLACK, fill=false)
    epd.show_region(16, 100, 64, 40);                                                  // Refresh a window, (x, y, w, h) → bool
                                                                                          // sends only the window's rows (RAM saving)
    bool ready = epd.wait_busy(1000);                                                  // Wait for BUSY high, (timeout_ms) → bool
                                                                                          // true when the controller is idle
    const uint8_t cdi = 0x97;
    epd.send_command(0x50, &cdi, 1);                                                   // Send raw command, (command, data*, len)
                                                                                          // rewrites CDI with the initialisation value
    epd.sleep();                                                                       // Enter deep sleep, () → bool
    epd.wake();                                                                        // Reset and re-initialise, () → bool
                                                                                          // framebuffer is kept
    PRINT("colour=%u status=%s%02X temperature=%s%d revision=%02X ready=%d\n", (unsigned)colour,
                haveStatus ? "0x" : "n/a ", status, haveTemp ? "" : "n/a ", temperature, revision, ready);
    (void)haveRev;
    PRINT("===DONE: 0 passed, 0 failed===\n");
    return 0;
}
