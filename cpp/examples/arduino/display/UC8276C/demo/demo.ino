// UC8276C demo example: Waveshare 4.2inch e-Paper (B), Arduino (full 400 x 300 frame on boards with >= 32 KB RAM; AVR boards run a 64 x 64 window).
// Wiring: DIN=MOSI, CLK=SCK, CS=10, DC=9, RST=8, BUSY=7
// Each refresh blocks for about 17 s.
// AVR boards have 8 KB of RAM: run a 64 x 64 window there (TRES), the full 400 x 300 elsewhere.
#if defined(__AVR__)
#define EPD_WIDTH  64
#define EPD_HEIGHT 64
#else
#define EPD_WIDTH  400
#define EPD_HEIGHT 300
#endif

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

static uint8_t bwBuffer[(EPD_WIDTH / 8) * EPD_HEIGHT];
static uint8_t redBuffer[(EPD_WIDTH / 8) * EPD_HEIGHT];

OutputPinArduino cs(10);                            // Create CS# pin, (pin)
OutputPinArduino dc(9);                             // Create D/C# pin, (pin)
OutputPinArduino res(8);                            // Create RES# pin, (pin)
InputPinArduino  busy(7);                           // Create BUSY_N pin, (pin)
DisplayConnection connection(SPI, &cs, &dc, &res, &busy);  // Create display connection, (spi, cs, dc, res, busy, settings, wireMode=kSimplex)
UC8276CFull<DisplayConnection> epd(connection, bwBuffer, redBuffer, EPD_WIDTH, EPD_HEIGHT);  // Create UC8276C driver, (connection, bw, red, width, height, busy_timeout_ms=30000)

void setup() {
    Serial.begin(115200);
    delay(2000);
    SPI.begin();
    if (!epd.valid()) {
        PRINT("UC8276C init failed: BUSY_N never went high\n");
        return;
    }
    typedef UC8276CFull<DisplayConnection> Epd;

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
}

void loop() {}
