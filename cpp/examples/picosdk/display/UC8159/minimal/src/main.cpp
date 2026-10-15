// UC8159 minimal example: Waveshare 5.65inch e-Paper (F), Raspberry Pi Pico SDK (Pico / Pico W).
// Wiring: DIN=GP19 (SPI0 TX), CLK=GP18, CS=GP17, DC=GP20, RST=GP21, BUSY=GP22
// Each refresh blocks for about 30 s.
#include <stdio.h>
#include <hardware/spi.h>
#include <hardware/gpio.h>
#include <pico/stdlib.h>
#include "DisplayConnectionPicoSDK.h"
#include "OutputPinPicoSDK.h"
#include "InputPinPicoSDK.h"
#include "UC8159.h"

#define PRINT printf
#define NOW_MS() to_ms_since_boot(get_absolute_time())

static uint8_t frameBuffer[100801];

int main(void) {
    stdio_init_all();
    sleep_ms(2000);

    spi_init(spi0, 4000 * 1000);
    gpio_set_function(18, GPIO_FUNC_SPI);   // SCK
    gpio_set_function(19, GPIO_FUNC_SPI);   // MOSI

    OutputPinPicoSDK cs(17);                  // Create CS# pin, (gpio)
    OutputPinPicoSDK dc(20);                  // Create D/C# pin, (gpio)
    OutputPinPicoSDK res(21);                 // Create RES# pin, (gpio)
    InputPinPicoSDK  busy(22);                // Create BUSY pin, (gpio)

    DisplayConnectionPicoSDK connection(spi0, &cs, &dc, &res, &busy);   // Create display connection, (spi, cs, dc, res, busy, wireMode=kSimplex)
    UC8159Minimal<DisplayConnectionPicoSDK> epd(connection, frameBuffer, sizeof(frameBuffer));   // Create UC8159 driver, (connection, buffer, buffer_len, busy_timeout_ms=60000)
    if (!epd.valid()) {
        PRINT("UC8159 init failed: BUSY never went high\n");
        return 1;
    }
    typedef UC8159Minimal<DisplayConnectionPicoSDK> Epd;
    epd.clear(Epd::WHITE);                                                             // Fill framebuffer, (color=WHITE)
    for (int16_t x = 0; x < 600; x++) {
        uint8_t colour = static_cast<uint8_t>((x * 7) / 600);                         // seven vertical bars: black .. orange
        for (int16_t y = 0; y < 448; y++) {
            epd.set_pixel(x, y, colour);                                               // Set one pixel, (x, y, color=BLACK)
        }
    }
    PRINT("refreshing, about 30 s ...\n");
    bool ok = epd.show();                                                              // Update display (about 30 s), () → bool
    PRINT(ok ? "done\n" : "BUSY timeout during refresh\n");
    epd.sleep();                                                                       // Enter deep sleep, () → bool
    return 0;
}
