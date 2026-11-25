// ST7036 demo example: EA DOGM162W-A (2 x 16), Zephyr (rpi_pico2, SPI0 + display-dc/res aliases from the board overlay), 3.3 V module
// Wiring: see cpp/boards/zephyr/rpi_pico2_rp2350a_m33.overlay; PSB=GND, E=3V3 (BUSY unused).
// Module capacitors: 0.1-1 uF between CAP1N and CAP1P, 0.47-2.2 uF from VOUT to GND, VIN = VDD.
// Demo: a two-line status display — a title line and a bar graph built from five custom characters that fills and empties, then a contrast sweep.
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

static void run(ST7036Full<DisplayConnectionZephyr>& lcd) {
    // --- Define the bar-graph glyphs ---
    // Custom characters 0..4 are columns of 1..5 filled pixel columns, so a 16-character bar
    // can show 80 steps with the eight CGRAM slots.
    for (uint8_t g = 0; g < 5; g++) {
        uint8_t rows[8];
        uint8_t pattern = static_cast<uint8_t>(0x1F << (4 - g)) & 0x1F;
        for (uint8_t r = 0; r < 8; r++) rows[r] = (r == 0 || r == 7) ? 0 : pattern;
        lcd.create_char(g, rows);                   // Define user character, (index 0..7, rows[8])
    }

    // --- Static title line ---
    lcd.clear();                                    // Clear display, ()
    lcd.print_at(0, 0, "Periph ST7036");            // Write text, (col, row, text)

    // --- Animate a progress bar on line 2 ---
    // Each step fills 1/5 of a character cell; a full bar is 16 x 5 = 80 steps.
    for (int step = 0; step <= 80; step++) {
        lcd.set_cursor(0, 1);                       // Move cursor, (col, row)
        for (int col = 0; col < 16; col++) {
            int fill = step - col * 5;              // pixel columns of this cell that are filled
            if (fill >= 5)      lcd.write_char(4);  // Write raw character code, (code)
            else if (fill > 0)  lcd.write_char(static_cast<uint8_t>(fill - 1));  // Write raw character code, (code)
            else                lcd.write_char(' ');  // Write raw character code, (code)
        }
        DELAY_MS(40);
    }
    DELAY_MS(1000);

    // --- Contrast sweep ---
    // The contrast is set once in normal use; sweeping it shows the usable range of the module.
    lcd.print_at(0, 0, "Contrast sweep ");          // Write text, (col, row, text)
    for (int c = 0x10; c <= 0x30; c++) {
        lcd.set_contrast(static_cast<uint8_t>(c));  // Set contrast, (value 0..63)
        DELAY_MS(80);
    }
    lcd.set_contrast(0x18);                         // Set contrast, (value 0..63)
    lcd.print_at(0, 0, "Periph ST7036   ");         // Write text, (col, row, text)
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
    ST7036Full<DisplayConnectionZephyr> lcd(connection);  // Create ST7036 driver, (connection, lines=2, supply=SUPPLY_3V3, contrast=-1 preset)
    if (!lcd.valid()) {
        PRINT("ST7036 init failed (check wiring)\n");
        return 1;
    }
    run(lcd);
    return 0;
}
