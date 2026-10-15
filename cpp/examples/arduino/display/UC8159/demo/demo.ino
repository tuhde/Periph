#if defined(__AVR__)
// The 100 801-byte framebuffer does not fit an AVR board.
void setup() {}
void loop() {}
#else
// UC8159 demo example: Waveshare 5.65inch e-Paper (F), Arduino (600 x 448, 3 bpp, 100 801 bytes: needs a board with >= 128 KB RAM).
// Wiring: DIN=MOSI, CLK=SCK, CS=10, DC=9, RST=8, BUSY=7
// Each refresh blocks for about 30 s.

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

static uint8_t frameBuffer[100801];

OutputPinArduino cs(10);                            // Create CS# pin, (pin)
OutputPinArduino dc(9);                             // Create D/C# pin, (pin)
OutputPinArduino res(8);                            // Create RES# pin, (pin)
InputPinArduino  busy(7);                           // Create BUSY pin, (pin)
DisplayConnection connection(SPI, &cs, &dc, &res, &busy);  // Create display connection, (spi, cs, dc, res, busy, settings, wireMode=kSimplex)
UC8159Full<DisplayConnection> epd(connection, frameBuffer, sizeof(frameBuffer));  // Create UC8159 driver, (connection, buffer, width, height, busy_timeout_ms=30000)

void setup() {
    Serial.begin(115200);
    delay(2000);
    SPI.begin();
    if (!epd.valid()) {
        PRINT("UC8159 init failed: BUSY never went high\n");
        return;
    }
    typedef UC8159Full<DisplayConnection> Epd;

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

}

void loop() {}

#endif
