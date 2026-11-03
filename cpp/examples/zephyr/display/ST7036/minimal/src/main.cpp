// ST7036 minimal example: EA DOGM162W-A (2 x 16), Zephyr (rpi_pico2, SPI0 + display-dc/res aliases from the board overlay), 3.3 V module
// Wiring: see cpp/boards/zephyr/rpi_pico2_rp2350a_m33.overlay; PSB=GND, E=3V3 (BUSY unused).
// Module capacitors: 0.1-1 uF between CAP1N and CAP1P, 0.47-2.2 uF from VOUT to GND, VIN = VDD.
// Minimal use: two lines of text.
#include <zephyr/kernel.h>
#include <zephyr/sys/printk.h>
#include <zephyr/drivers/spi.h>
#include "DisplayConnectionZephyr.h"
#include "OutputPinZephyr.h"
#include "InputPinZephyr.h"
#include "ST7036.h"

#define DELAY_MS(ms) k_msleep(ms)
#define PRINT printk
#define NOW_MS() ((uint32_t)k_uptime_get())

static void run(ST7036Minimal<DisplayConnectionZephyr>& lcd) {
    lcd.print_at(0, 0, "Hello, Periph!");              // Write text, (col, row, text)
    lcd.print_at(0, 1, "ST7036 DOGM162");              // Write text, (col, row, text)
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
    ST7036Minimal<DisplayConnectionZephyr> lcd(connection);  // Create ST7036 driver, (connection)
    if (!lcd.valid()) {
        PRINT("ST7036 init failed (check wiring)\n");
        return 1;
    }
    run(lcd);
    return 0;
}
