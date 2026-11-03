#if defined(__AVR__)
// The 12 288-byte framebuffer does not fit an AVR board.
void setup() {}
void loop() {}
#else
// SSD1331 minimal example: Waveshare 0.95inch RGB OLED, Arduino (96 x 64 RGB565, 12 288 bytes: needs a board with more than 16 KB RAM)
// Wiring: DIN=MOSI, CLK=SCK, CS=10, DC=9, RST=8 (no BUSY line).
// Minimal use: eight colour bars and a white frame, drawn into the framebuffer and sent once.
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
#define DELAY_MS(ms) delay(ms)
#define NOW_MS() ((uint32_t)millis())

static uint8_t frameBuffer[96 * 64 * 2];

OutputPinArduino cs(10);                            // Create CS# pin, (pin)
OutputPinArduino dc(9);                             // Create D/C# pin, (pin)
OutputPinArduino res(8);                            // Create RES# pin, (pin)
DisplayConnection connection(SPI, &cs, &dc, &res, nullptr);  // Create display connection, (spi, cs, dc, res, busy, settings, wireMode=kSimplex)
SSD1331Minimal<DisplayConnection> oled(connection, frameBuffer);  // Create SSD1331 driver, (connection, buffer)

static void run(SSD1331Minimal<DisplayConnection>& oled) {
    static const uint16_t bars[8] = {
        oled.WHITE, oled.YELLOW, oled.CYAN, oled.GREEN, oled.MAGENTA, oled.RED, oled.BLUE, oled.BLACK
    };
    for (int16_t y = 0; y < oled.height(); y++) {
        for (int16_t x = 0; x < oled.width(); x++) {
            oled.set_pixel(x, y, bars[x / 12]);     // Set pixel, (x, y, color=WHITE)
        }
    }
    for (int16_t x = 0; x < oled.width(); x++) {
        oled.set_pixel(x, 0, oled.WHITE);           // Set pixel, (x, y, color=WHITE)
        oled.set_pixel(x, oled.height() - 1, oled.WHITE);
    }

    oled.show();                                    // Send framebuffer, () → None
    PRINT("done\n");
}

void setup() {
    Serial.begin(115200);
    delay(2000);
    SPI.begin();
    if (!oled.valid()) {
        PRINT("SSD1331 init failed (check wiring)\n");
        return;
    }
    run(oled);
}

void loop() {}
#endif
