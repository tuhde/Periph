// IL0373 demo example: Waveshare 2.13inch e-Paper (D), Arduino (104 x 212 frame, 2756 bytes).
// Wiring: DIN=MOSI, CLK=SCK, CS=10, DC=9, RST=8, BUSY=7
// Each refresh blocks until BUSY goes high (the panel datasheet gives no update time).
#define EPD_WIDTH  104
#define EPD_HEIGHT 212

#include <stdarg.h>
#include <stdio.h>
#include <SPI.h>
#include <Periph.h>

static void PRINT(const char* fmt, ...) {
    char buf[96];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    Serial.print(buf);
}
#define NOW_MS() ((uint32_t)millis())

static uint8_t frameBuffer[(EPD_WIDTH / 8) * EPD_HEIGHT];

OutputPinArduino cs(10);                            // Create CS# pin, (pin)
OutputPinArduino dc(9);                             // Create D/C# pin, (pin)
OutputPinArduino res(8);                            // Create RES# pin, (pin)
InputPinArduino  busy(7);                           // Create BUSY pin, (pin)
DisplayConnection connection(SPI, &cs, &dc, &res, &busy);  // Create display connection, (spi, cs, dc, res, busy, settings, wireMode=kSimplex)
IL0373Full<DisplayConnection> epd(connection, frameBuffer, EPD_WIDTH, EPD_HEIGHT);  // Create IL0373 driver, (connection, buffer, width, height, busy_timeout_ms=30000)

void setup() {
    Serial.begin(115200);
    delay(2000);
    SPI.begin();
    if (!epd.valid()) {
        PRINT("IL0373 init failed: BUSY never went high\n");
        return;
    }
    typedef IL0373Full<DisplayConnection> Epd;

    // --- Compose the status card in the framebuffer ---
    // White page, a one-pixel black border inset by 4 px (a frame on the very edge is
    // hidden by the panel bezel) and a black header bar across the top 24 rows.
    epd.clear(Epd::WHITE);                                                             // Fill framebuffer, (color=WHITE)
    epd.draw_rect(2, 2, 100, 208, Epd::BLACK);                                         // Draw rectangle, (x, y, w, h, color=BLACK, fill=false)
    epd.draw_rect(3, 3, 98, 24, Epd::BLACK, true);                                      // Draw rectangle, (x, y, w, h, color=BLACK, fill=false)

    // --- Bar chart of eight readings ---
    // Axes first, then one filled bar per value; the tallest bar is filled and the others are outlines.
    static const int16_t values[8] = {30, 55, 80, 45, 120, 90, 60, 100};
    int16_t peak = 0;
    for (int i = 0; i < 8; i++) {
        if (values[i] > peak) peak = values[i];
    }
    epd.draw_line(10, 190, 95, 190, Epd::BLACK);                                      // Draw line, (x0, y0, x1, y1, color=BLACK)
    epd.draw_line(10, 60, 10, 190, Epd::BLACK);                                        // Draw line, (x0, y0, x1, y1, color=BLACK)
    for (int i = 0; i < 8; i++) {
        epd.draw_rect(14 + i * 10, 190 - values[i], 8, values[i], Epd::BLACK, values[i] == peak); // Draw rectangle, (x, y, w, h, color=BLACK, fill=false)
    }

    // --- Refresh and time it ---
    // show() blocks for the whole DTM1/DTM2/DRF/POF sequence.
    uint32_t t0 = NOW_MS();
    bool ok = epd.show();                                                              // Update display, () → bool
    uint32_t elapsed = NOW_MS() - t0;
    PRINT("%s after %lu ms\n", ok ? "refresh took" : "BUSY timeout", (unsigned long)elapsed);

    // --- Save the image and power down ---
    // The panel keeps the picture without power; deep sleep stops the controller drawing current.
    epd.sleep();                                                                       // Enter deep sleep, () → bool
}

void loop() {}
