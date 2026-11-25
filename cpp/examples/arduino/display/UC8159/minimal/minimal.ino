#if defined(__AVR__)
// The 100 801-byte framebuffer does not fit an AVR board.
void setup() {}
void loop() {}
#else
// UC8159 minimal example: Waveshare 5.65inch e-Paper (F), Arduino (600 x 448, 3 bpp, 100 801 bytes: needs a board with >= 128 KB RAM).
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
UC8159Minimal<DisplayConnection> epd(connection, frameBuffer, sizeof(frameBuffer));  // Create UC8159 driver, (connection, buffer, width, height, busy_timeout_ms=30000)

void setup() {
    Serial.begin(115200);
    delay(2000);
    SPI.begin();
    if (!epd.valid()) {
        PRINT("UC8159 init failed: BUSY never went high\n");
        return;
    }
    typedef UC8159Minimal<DisplayConnection> Epd;
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

}

void loop() {}

#endif
