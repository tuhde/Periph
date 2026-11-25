// IL0373 minimal example: Waveshare 2.13inch e-Paper (D), Arduino (104 x 212 frame, 2756 bytes).
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
IL0373Minimal<DisplayConnection> epd(connection, frameBuffer, EPD_WIDTH, EPD_HEIGHT);  // Create IL0373 driver, (connection, buffer, width, height, busy_timeout_ms=30000)

void setup() {
    Serial.begin(115200);
    delay(2000);
    SPI.begin();
    if (!epd.valid()) {
        PRINT("IL0373 init failed: BUSY never went high\n");
        return;
    }
    typedef IL0373Minimal<DisplayConnection> Epd;
    epd.clear(Epd::WHITE);                                                             // Fill framebuffer, (color=WHITE)
    epd.set_pixel(10, 10, Epd::BLACK);                                                 // Set pixel, (x, y, color=BLACK)
    for (int16_t x = 20; x < 100; x++) {
        epd.set_pixel(x, 40, Epd::BLACK);                                                // Set pixel, (x, y, color=BLACK)
    }
    epd.show();                                                                        // Update display, () → bool
    epd.sleep();                                                                       // Enter deep sleep, () → bool
}

void loop() {}
