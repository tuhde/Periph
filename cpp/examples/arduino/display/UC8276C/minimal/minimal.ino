// UC8276C minimal example: Waveshare 4.2inch e-Paper (B), Arduino (full 400 x 300 frame on boards with >= 32 KB RAM; AVR boards run a 64 x 64 window).
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
UC8276CMinimal<DisplayConnection> epd(connection, bwBuffer, redBuffer, EPD_WIDTH, EPD_HEIGHT);  // Create UC8276C driver, (connection, bw, red, width, height, busy_timeout_ms=30000)

void setup() {
    Serial.begin(115200);
    delay(2000);
    SPI.begin();
    if (!epd.valid()) {
        PRINT("UC8276C init failed: BUSY_N never went high\n");
        return;
    }
    typedef UC8276CMinimal<DisplayConnection> Epd;
    epd.clear(Epd::WHITE);                                                             // Fill framebuffer, (color=WHITE)
    epd.set_pixel(10, 10, Epd::BLACK);                                                 // Set pixel, (x, y, color=BLACK)
    for (int16_t x = 20; x < 120; x++) {
        epd.set_pixel(x, 40, Epd::RED);                                                // Set pixel, (x, y, color=BLACK)
    }
    epd.show();                                                                        // Update display (about 17 s), () → bool
    epd.sleep();                                                                       // Enter deep sleep, () → bool
}

void loop() {}
