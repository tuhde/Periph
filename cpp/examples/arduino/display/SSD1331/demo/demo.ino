#if defined(__AVR__)
// The 12 288-byte framebuffer does not fit an AVR board.
void setup() {}
void loop() {}
#else
// SSD1331 demo example: Waveshare 0.95inch RGB OLED, Arduino (96 x 64 RGB565, 12 288 bytes: needs a board with more than 16 KB RAM)
// Wiring: DIN=MOSI, CLK=SCK, CS=10, DC=9, RST=8 (no BUSY line).
// Demo: a colour test card — eight colour bars over a gray ramp, a frame, a marker drawn with the controller's own rectangle command, then a slow scroll.
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
SSD1331Full<DisplayConnection> oled(connection, frameBuffer);  // Create SSD1331 driver, (connection, buffer)

static void run(SSD1331Full<DisplayConnection>& oled) {
    typedef SSD1331Full<DisplayConnection> Chip;
    // --- Draw the test card into the framebuffer ---
    // Eight 12-pixel colour bars over a 32-row gray ramp show that all channels and the
    // 5-6-5 depth work; the one-pixel frame shows that the whole 96 x 64 area is addressed.
    static const uint16_t bars[8] = {
        Chip::WHITE, Chip::YELLOW, Chip::CYAN, Chip::GREEN, Chip::MAGENTA, Chip::RED, Chip::BLUE, Chip::BLACK
    };
    oled.clear(Chip::BLACK);                        // Fill framebuffer, (color=BLACK)
    for (int16_t x = 0; x < 96; x++) {
        oled.draw_rect(x, 2, 1, 30, bars[x / 12], true);  // Software rectangle, (x, y, w, h, color, fill=false)
        uint8_t g = static_cast<uint8_t>(x * 255 / 95);
        oled.draw_rect(x, 34, 1, 26, Chip::rgb(g, g, g), true);  // Software rectangle, (x, y, w, h, color, fill=false)
    }
    oled.draw_rect(0, 0, 96, 64, Chip::WHITE);      // Software rectangle, (x, y, w, h, color, fill=false)

    // --- Send it once ---
    oled.show();                                    // Send framebuffer, () → None
    DELAY_MS(3000);

    // --- Animate with hardware drawing ---
    // The chip draws the marker straight into its RAM: no framebuffer transfer per step.
    for (int step = 0; step < 60; step++) {
        int x = 4 + (step * 3) % 80;
        oled.show();                                // Send framebuffer, () → None
        oled.hw_rect(static_cast<uint8_t>(x), 36, 10, 10, Chip::WHITE, Chip::RED, true);  // Hardware rectangle, (x, y, w, h, line_color, fill_color=0, fill=false)
        DELAY_MS(50);
    }

    // --- Scroll the test card ---
    // The chip scrolls its own RAM, one column every 6 frames.
    oled.show();                                    // Send framebuffer, () → None
    oled.scroll(1, 0, 64, 0, 0);                    // Scroll, (horizontal, start_row, rows, vertical, interval 0..3)
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
