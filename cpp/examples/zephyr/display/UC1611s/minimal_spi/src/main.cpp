// UC1611s minimal example (4-wire SPI): EA DOGXL240-7, Zephyr (rpi_pico2, SPI0 + display-dc/res aliases from the board overlay)
// Wiring: see cpp/boards/zephyr/rpi_pico2_rp2350a_m33.overlay; VDD=3V3 (no BUSY line).
// Module: EA DOGXL240-7 (240 x 128, 3.3 V only), 4-wire SPI: BM0 = D13 = VSS. Capacitors: 4 x 4.7 uF/5 V (VA0, VA1, VB0, VB1 pairs), 470 nF/25 V on VLCD.
// Minimal use: a border and a diagonal on the 240 x 128 panel.
#include <zephyr/kernel.h>
#include <zephyr/sys/printk.h>
#include <zephyr/drivers/spi.h>
#include "DisplayConnectionZephyr.h"
#include "OutputPinZephyr.h"
#include "InputPinZephyr.h"
#include "UC1611s.h"

#define DELAY_MS(ms) k_msleep(ms)
#define PRINT printk
#define NOW_MS() ((uint32_t)k_uptime_get())

static void run(UC1611sSpiMinimal<DisplayConnectionZephyr>& lcd) {
    lcd.clear();                                    // Clear framebuffer, ()
    for (int16_t x = 0; x < 240; x++) {
        lcd.set_pixel(x, 0);                        // Set pixel, (x 0..239, y 0..127, on=true)
        lcd.set_pixel(x, 127);                      // Set pixel, (x 0..239, y 0..127, on=true)
        lcd.set_pixel(x, static_cast<int16_t>(x * 127 / 239));  // Set pixel, (x 0..239, y 0..127, on=true)
    }
    for (int16_t y = 0; y < 128; y++) {
        lcd.set_pixel(0, y);                        // Set pixel, (x 0..239, y 0..127, on=true)
        lcd.set_pixel(239, y);                      // Set pixel, (x 0..239, y 0..127, on=true)
    }
    lcd.show();                                     // Send framebuffer to the display, ()
    PRINT("done\n");
}

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

    OutputPinZephyr dc(dcSpec);                       // Create D/C# pin, (gpio_dt_spec)
    OutputPinZephyr res(resSpec);                     // Create RES# pin, (gpio_dt_spec)

    DisplayConnectionZephyr connection(spi_dev, spi_cfg, nullptr, &dc, &res, nullptr);   // Create display connection, (spi_dev, spi_cfg, cs=nullptr, dc, res, busy, wireMode=kSimplex)
    UC1611sSpiMinimal<DisplayConnectionZephyr> lcd(connection);  // Create UC1611s driver, (connection, width=240, height=128)
    if (!lcd.valid()) {
        PRINT("UC1611s init failed (check wiring)\n");
        return 1;
    }
    run(lcd);
    return 0;
}
