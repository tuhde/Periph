// UC1611s demo example (4-wire SPI): EA DOGXL240-7, Zephyr (rpi_pico2, SPI0 + display-dc/res aliases from the board overlay)
// Wiring: see cpp/boards/zephyr/rpi_pico2_rp2350a_m33.overlay; VDD=3V3 (no BUSY line).
// Module: EA DOGXL240-7 (240 x 128, 3.3 V only), 4-wire SPI: BM0 = D13 = VSS. Capacitors: 4 x 4.7 uF/5 V (VA0, VA1, VB0, VB1 pairs), 470 nF/25 V on VLCD.
// Demo: a bouncing ball with partial page updates, a contrast sweep, inverse flashes, hardware scroll, a second RAM screen and sleep.
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

static void run(UC1611sSpiFull<DisplayConnectionZephyr>& lcd) {
    // --- Draw the frame once ---
    // A one-pixel border around the whole 240 x 128 panel; the ball bounces inside it.
    lcd.clear();                                    // Clear framebuffer, ()
    lcd.draw_rect(0, 0, 240, 128);                  // Draw rectangle, (x, y, w, h, on=true, fill=false)
    lcd.show();                                     // Send framebuffer to the display, ()
    
    // --- Bounce an 8 x 8 ball with partial page updates ---
    // Only the page rows the ball touches are re-sent, so the animation runs at about
    // 20 frames per second, instead of the 8 of a full-frame show().
    int16_t bx = 20, by = 12, dx = 3, dy = 2;
    for (int frame = 0; frame < 200; frame++) {
        int16_t oldy = by;
        lcd.draw_rect(bx, by, 8, 8, false, true);   // Draw rectangle, (x, y, w, h, on=true, fill=false)
        bx += dx;
        by += dy;
        if (bx < 2 || bx > 229) { dx = -dx; bx += 2 * dx; }
        if (by < 2 || by > 118) { dy = -dy; by += 2 * dy; }
        lcd.draw_rect(bx, by, 8, 8, true, true);    // Draw rectangle, (x, y, w, h, on=true, fill=false)
        int16_t first = (oldy < by ? oldy : by) / 8;
        int16_t last = ((oldy > by ? oldy : by) + 7) / 8;
        for (int16_t page = first; page <= last; page++) {
            lcd.show_page(static_cast<uint8_t>(page));  // Send one framebuffer page, (page 0..15)
        }
        DELAY_MS(20);
    }
    
    // --- Contrast sweep around the working point ---
    // The contrast is set once in normal use; sweeping PM shows the usable range of the panel.
    for (int pm = 0x8F; pm >= 0x70; pm -= 2) {
        lcd.set_contrast(static_cast<uint8_t>(pm));  // Set contrast, (value 0..255)
        DELAY_MS(40);
    }
    for (int pm = 0x70; pm <= 0x8F; pm += 2) {
        lcd.set_contrast(static_cast<uint8_t>(pm));  // Set contrast, (value 0..255)
        DELAY_MS(40);
    }
    
    // --- Flash the inverse display twice ---
    for (int i = 0; i < 2; i++) {
        lcd.set_invert(true);                       // Invert display, (on)
        DELAY_MS(400);
        lcd.set_invert(false);                      // Invert display, (on)
        DELAY_MS(400);
    }
    
    // --- Hardware scroll ---
    // The image moves up in the controller, no data is re-sent; a blank band enters from the bottom
    // because RAM rows 128..159 are empty.
    for (uint8_t line = 0; line <= 32; line++) {
        lcd.set_scroll_line(line);                  // Scroll the image up, (line 0..159 - 2 * fixed)
        DELAY_MS(40);
    }
    for (int line = 32; line >= 0; line--) {
        lcd.set_scroll_line(static_cast<uint8_t>(line));  // Scroll the image up, (line 0..159 - 2 * fixed)
        DELAY_MS(40);
    }
    
    // --- Second screen in RAM ---
    // The chip holds four black/white screens; draw a cross into screen 1 and switch to it.
    lcd.set_write_pattern(1);                       // Select RAM screen to write, (pattern 0..3)
    lcd.fill(false);                                // Fill framebuffer, (on=true)
    lcd.draw_line(0, 0, 239, 127);                  // Draw line, (x0, y0, x1, y1, on=true)
    lcd.draw_line(0, 127, 239, 0);                  // Draw line, (x0, y0, x1, y1, on=true)
    lcd.show();                                     // Send framebuffer to the display, ()
    lcd.set_display_pattern(1);                     // Select displayed RAM screen, (pattern 0..3)
    DELAY_MS(2000);
    lcd.set_display_pattern(0);                     // Select displayed RAM screen, (pattern 0..3)
    lcd.set_write_pattern(0);                       // Select RAM screen to write, (pattern 0..3)
    DELAY_MS(1500);
    
    // --- Power down ---
    // Sleep stops the charge pump and the drivers (below 1 uA); RAM and registers are kept.
    lcd.sleep();                                    // Enter sleep, ()
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
    UC1611sSpiFull<DisplayConnectionZephyr> lcd(connection);  // Create UC1611s driver, (connection, width=240, height=128, topView=false, contrast=0x8F)
    if (!lcd.valid()) {
        PRINT("UC1611s init failed (check wiring)\n");
        return 1;
    }
    run(lcd);
    return 0;
}
