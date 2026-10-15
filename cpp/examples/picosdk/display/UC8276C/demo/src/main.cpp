// UC8276C demo example: Waveshare 4.2inch e-Paper (B), Raspberry Pi Pico SDK (Pico / Pico W).
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
    UC8276CFull<DisplayConnectionPicoSDK> epd(connection, bwBuffer, redBuffer);   // Create UC8276C driver, (connection, bw, red, width=400, height=300, busy_timeout_ms=30000)
    if (!epd.valid()) {
        PRINT("UC8276C init failed: BUSY_N never went high\n");
        return 1;
    }
    typedef UC8276CFull<DisplayConnectionPicoSDK> Epd;

    // --- Compose the status card in the framebuffer ---
    // White page, a one-pixel black border inset by 4 px (a frame on the very edge is
    // hidden by the panel bezel) and a red header bar across the top 40 rows.
    epd.clear(Epd::WHITE);                                                             // Fill framebuffer, (color=WHITE)
    epd.draw_rect(4, 4, 392, 292, Epd::BLACK);                                         // Draw rectangle, (x, y, w, h, color=BLACK, fill=false)
    epd.draw_rect(5, 5, 390, 40, Epd::RED, true);                                      // Draw rectangle, (x, y, w, h, color=BLACK, fill=false)

    // --- Bar chart of eight readings ---
    // Axes first, then one filled bar per value; the tallest bar is red to flag the peak.
    static const int16_t values[8] = {30, 55, 80, 45, 120, 90, 60, 100};
    int16_t peak = 0;
    for (int i = 0; i < 8; i++) {
        if (values[i] > peak) peak = values[i];
    }
    epd.draw_line(30, 270, 370, 270, Epd::BLACK);                                      // Draw line, (x0, y0, x1, y1, color=BLACK)
    epd.draw_line(30, 70, 30, 270, Epd::BLACK);                                        // Draw line, (x0, y0, x1, y1, color=BLACK)
    for (int i = 0; i < 8; i++) {
        epd.draw_rect(45 + i * 40, 270 - values[i], 28, values[i], values[i] == peak ? Epd::RED : Epd::BLACK, true); // Draw rectangle, (x, y, w, h, color=BLACK, fill=false)
    }

    // --- Refresh and time it ---
    // show() blocks for the whole DTM1/DTM2/PON/DRF/POF sequence.
    uint32_t t0 = NOW_MS();
    bool ok = epd.show();                                                              // Update display, () → bool
    uint32_t elapsed = NOW_MS() - t0;
    PRINT("%s after %lu ms\n", ok ? "refresh took" : "BUSY_N timeout", (unsigned long)elapsed);

    // --- Save the image and power down ---
    // The panel keeps the picture without power; deep sleep stops the controller drawing current.
    epd.sleep();                                                                       // Enter deep sleep, () → bool
    return 0;
}
