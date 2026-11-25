// SSD1331 complete example: Waveshare 0.95inch RGB OLED, Raspberry Pi Pico SDK (Pico / Pico W)
// Wiring: DIN=GP19 (SPI0 TX), CLK=GP18, CS=GP17, DC=GP20, RST=GP21 (no BUSY line).
// Complete: every Full method — software drawing, partial update, hardware line/rectangle/copy/dim/clear, scrolling, contrast, master current, flip, display modes, power save.
#include <stdio.h>
#include <hardware/spi.h>
#include <hardware/gpio.h>
#include <pico/stdlib.h>
#include "DisplayConnectionPicoSDK.h"
#include "OutputPinPicoSDK.h"
#include "InputPinPicoSDK.h"
#include "SSD1331.h"

#define DELAY_MS(ms) sleep_ms(ms)
#define PRINT printf
#define NOW_MS() to_ms_since_boot(get_absolute_time())

static uint8_t frameBuffer[96 * 64 * 2];

static void run(SSD1331Full<DisplayConnectionPicoSDK>& oled) {
    typedef SSD1331Full<DisplayConnectionPicoSDK> Chip;
    oled.clear(Chip::BLACK);                        // Fill framebuffer, (color=BLACK)
                                                    // sets every pixel; the display is unchanged until show()
    oled.draw_rect(0, 0, 96, 64, Chip::WHITE);      // Software rectangle, (x, y, w, h, color, fill=false)
                                                    // outline into the framebuffer
    oled.draw_rect(4, 4, 30, 20, Chip::RED, true);  // Software rectangle, (x, y, w, h, color, fill=false)
                                                    // filled block
    oled.draw_line(0, 0, 95, 63, Chip::GREEN);      // Software line, (x0, y0, x1, y1, color)
                                                    // Bresenham diagonal
    oled.draw_line(0, 63, 95, 0, Chip::CYAN);       // Software line, (x0, y0, x1, y1, color)
    uint16_t sprite[8 * 8];
    for (int i = 0; i < 64; i++) sprite[i] = (((i / 8) + (i % 8)) & 1) ? Chip::YELLOW : Chip::MAGENTA;
    oled.blit(60, 8, sprite, 8, 8);                 // Copy bitmap, (x, y, bitmap, w, h)
                                                    // RGB565 checkerboard into the framebuffer
    oled.set_pixel(48, 32, Chip::WHITE);            // Set pixel, (x, y, color=WHITE)
    uint16_t c = oled.get_pixel(48, 32);            // Read framebuffer pixel, (x, y) → int RGB565
    PRINT("pixel 0x%04X\n", c);
    oled.show();                                    // Send framebuffer, () → None
                                                    // window 0..95 x 0..63 then 12288 bytes
    DELAY_MS(2000);

    oled.clear(Chip::BLUE);                         // Fill framebuffer, (color=BLACK)
    oled.draw_rect(20, 16, 56, 32, Chip::WHITE, true);  // Software rectangle, (x, y, w, h, color, fill=false)
    oled.show_region(16, 12, 64, 40);               // Send region, (x, y, w, h)
                                                    // only the rows of the window are transferred
    DELAY_MS(2000);

    oled.hw_clear(0, 0, 95, 63);                    // Clear window in display RAM, (x0, y0, x1, y1)
                                                    // chip clears its own RAM, framebuffer untouched
    oled.hw_rect(10, 10, 76, 44, Chip::YELLOW, Chip::RED, true);  // Hardware rectangle, (x, y, w, h, line_color, fill_color=0, fill=false)
                                                    // outline and fill drawn by the chip
    oled.hw_line(0, 0, 95, 63, Chip::WHITE);        // Hardware line, (x0, y0, x1, y1, color)
    DELAY_MS(2000);
    oled.hw_copy(10, 10, 40, 40, 50, 20);           // Hardware copy, (x0, y0, x1, y1, x, y)
    DELAY_MS(2000);
    oled.hw_dim(0, 0, 47, 63);                      // Hardware dim window, (x0, y0, x1, y1)
    DELAY_MS(2000);

    oled.set_display_mode(Chip::MODE_INVERSE);      // Display mode, (mode)
    DELAY_MS(1500);
    oled.set_display_mode(Chip::MODE_ALL_ON);       // Display mode, (mode)
    DELAY_MS(1000);
    oled.set_display_mode(Chip::MODE_ALL_OFF);      // Display mode, (mode)
    DELAY_MS(1000);
    oled.set_display_mode(Chip::MODE_NORMAL);       // Display mode, (mode)

    oled.set_contrast(0x30, 0x30, 0x30);            // Channel contrast, (a, b, c) 0..255
    DELAY_MS(1000);
    oled.set_contrast(0xFF, 0xFF, 0xFF);            // Channel contrast, (a, b, c) 0..255
    oled.set_master_current(4);                     // Master current, (n 1..16) n/16
    DELAY_MS(1000);
    oled.set_master_current(7);                     // Master current, (n 1..16) n/16

    oled.set_flip(false, true);                     // Mirror, (horizontal, vertical)
    oled.show();                                    // Send framebuffer, () → None
    DELAY_MS(1500);
    oled.set_flip(true, true);                      // Mirror, (horizontal, vertical)
    oled.show();                                    // Send framebuffer, () → None
    DELAY_MS(1500);

    oled.scroll(1, 0, 64, 0, 0);                    // Scroll, (horizontal, start_row, rows, vertical, interval 0..3)
                                                    // continuous scroll one column per 6 frames
    DELAY_MS(4000);
    oled.scroll_stop();                             // Stop scrolling, ()

    oled.dim_mode(0x10, 0x10, 0x10, 0x10);          // Dim mode, (a, b, c, precharge 0..31)
    DELAY_MS(1500);
    oled.set_power_save(true);                      // Power save, (on)
    oled.display_off();                             // Display off, ()
    PRINT("done\n");
}

int main(void) {
    stdio_init_all();
    sleep_ms(2000);

    spi_init(spi0, 4000 * 1000);
    gpio_set_function(18, GPIO_FUNC_SPI);   // SCK
    gpio_set_function(19, GPIO_FUNC_SPI);   // MOSI

    OutputPinPicoSDK cs(17);                  // Create CS# pin, (gpio)
    OutputPinPicoSDK dc(20);                  // Create D/C# pin, (gpio)
    OutputPinPicoSDK res(21);                 // Create RES# pin, (gpio)

    DisplayConnectionPicoSDK connection(spi0, &cs, &dc, &res, nullptr);   // Create display connection, (spi, cs, dc, res, busy, wireMode=kSimplex)
    SSD1331Full<DisplayConnectionPicoSDK> oled(connection, frameBuffer);  // Create SSD1331 driver, (connection, buffer)
    if (!oled.valid()) {
        PRINT("SSD1331 init failed (check wiring)\n");
        return 1;
    }
    run(oled);
    return 0;
}
