// UC8276C minimal example: Waveshare 4.2inch e-Paper (B), Raspberry Pi Pico SDK (Pico / Pico W).
// Wiring: DIN=GP19 (SPI0 TX), CLK=GP18, CS=GP17, DC=GP20, RST=GP21, BUSY=GP22
// Each refresh blocks for about 17 s.
#include <stdio.h>
#include <hardware/spi.h>
#include <hardware/gpio.h>
#include <pico/stdlib.h>
#include "DisplayConnectionPicoSDK.h"
#include "OutputPinPicoSDK.h"
#include "InputPinPicoSDK.h"
#include "UC8276C.h"

#define PRINT printf
#define NOW_MS() to_ms_since_boot(get_absolute_time())

static uint8_t bwBuffer[15000];
static uint8_t redBuffer[15000];

int main(void) {
    stdio_init_all();
    sleep_ms(2000);

    spi_init(spi0, 4000 * 1000);
    gpio_set_function(18, GPIO_FUNC_SPI);   // SCK
    gpio_set_function(19, GPIO_FUNC_SPI);   // MOSI

    OutputPinPicoSDK cs(17);                  // Create CS# pin, (gpio)
    OutputPinPicoSDK dc(20);                  // Create D/C# pin, (gpio)
    OutputPinPicoSDK res(21);                 // Create RES# pin, (gpio)
    InputPinPicoSDK  busy(22);                // Create BUSY_N pin, (gpio)

    DisplayConnectionPicoSDK connection(spi0, &cs, &dc, &res, &busy);   // Create display connection, (spi, cs, dc, res, busy, wireMode=kSimplex)
    UC8276CMinimal<DisplayConnectionPicoSDK> epd(connection, bwBuffer, redBuffer);   // Create UC8276C driver, (connection, bw, red, width=400, height=300, busy_timeout_ms=30000)
    if (!epd.valid()) {
        PRINT("UC8276C init failed: BUSY_N never went high\n");
        return 1;
    }
    typedef UC8276CMinimal<DisplayConnectionPicoSDK> Epd;
    epd.clear(Epd::WHITE);                                                             // Fill framebuffer, (color=WHITE)
    epd.set_pixel(10, 10, Epd::BLACK);                                                 // Set pixel, (x, y, color=BLACK)
    for (int16_t x = 20; x < 120; x++) {
        epd.set_pixel(x, 40, Epd::RED);                                                // Set pixel, (x, y, color=BLACK)
    }
    epd.show();                                                                        // Update display (about 17 s), () → bool
    epd.sleep();                                                                       // Enter deep sleep, () → bool
    return 0;
}
