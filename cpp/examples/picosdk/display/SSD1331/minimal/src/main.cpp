// SSD1331 minimal example: Waveshare 0.95inch RGB OLED, Raspberry Pi Pico SDK (Pico / Pico W)
// Wiring: DIN=GP19 (SPI0 TX), CLK=GP18, CS=GP17, DC=GP20, RST=GP21 (no BUSY line).
// Minimal use: eight colour bars and a white frame, drawn into the framebuffer and sent once.
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

static void run(SSD1331Minimal<DisplayConnectionPicoSDK>& oled) {
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
    stdio_init_all();
    sleep_ms(2000);

    spi_init(spi0, 4000 * 1000);
    gpio_set_function(18, GPIO_FUNC_SPI);   // SCK
    gpio_set_function(19, GPIO_FUNC_SPI);   // MOSI

    OutputPinPicoSDK cs(17);                  // Create CS# pin, (gpio)
    OutputPinPicoSDK dc(20);                  // Create D/C# pin, (gpio)
    OutputPinPicoSDK res(21);                 // Create RES# pin, (gpio)

    DisplayConnectionPicoSDK connection(spi0, &cs, &dc, &res, nullptr);   // Create display connection, (spi, cs, dc, res, busy, wireMode=kSimplex)
    SSD1331Minimal<DisplayConnectionPicoSDK> oled(connection, frameBuffer);  // Create SSD1331 driver, (connection, buffer)
    if (!oled.valid()) {
        PRINT("SSD1331 init failed (check wiring)\n");
        return 1;
    }
    run(oled);
    return 0;
}
