// UC8159 demo example: Waveshare 5.65inch e-Paper (F), Raspberry Pi Pico SDK (Pico / Pico W).
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
    UC8159Full<DisplayConnectionPicoSDK> epd(connection, frameBuffer, sizeof(frameBuffer));   // Create UC8159 driver, (connection, buffer, buffer_len, busy_timeout_ms=60000)
    if (!epd.valid()) {
        PRINT("UC8159 init failed: BUSY never went high\n");
        return 1;
    }
    typedef UC8159Full<DisplayConnectionPicoSDK> Epd;

    // --- Compose the colour chart in the framebuffer ---
    // White page, seven vertical bars (black, white, green, blue, red, yellow, orange) and a
    // one-pixel black frame inset by 4 px (a frame on the very edge can hide behind the bezel).
    epd.clear(Epd::WHITE);                                                             // Fill framebuffer, (color=WHITE)
    for (uint8_t c = 0; c < 7; c++) {
        epd.draw_rect(20 + c * 80, 40, 80, 368, c, true);                              // Draw rectangle, (x, y, w, h, color, fill)
    }
    epd.draw_rect(4, 4, 592, 440, Epd::BLACK);                                         // Draw rectangle, (x, y, w, h, color, fill=false)

    // --- Refresh and time it ---
    // show() blocks for the whole DTM1/PON/DRF/POF sequence: about 30 s.
    uint32_t t0 = NOW_MS();
    bool ok = epd.show();                                                              // Update display, () → bool
    uint32_t elapsed = NOW_MS() - t0;
    PRINT("%s after %lu ms\n", ok ? "refresh took" : "BUSY timeout", (unsigned long)elapsed);

    // --- Save the image and power down ---
    // The panel keeps the picture without power; deep sleep stops the controller drawing current.
    epd.sleep();                                                                       // Enter deep sleep, () → bool
    return 0;
}
