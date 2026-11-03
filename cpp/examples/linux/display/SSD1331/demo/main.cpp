// SSD1331 demo example: Waveshare 0.95inch RGB OLED, Raspberry Pi (Linux GCC)
// Wiring: DIN=GPIO10 (SPI0 MOSI), CLK=GPIO11, CS=GPIO8 (CE0), DC=GPIO25, RST=GPIO17 (no BUSY line).
// Demo: a colour test card — eight colour bars over a gray ramp, a frame, a marker drawn with the controller's own rectangle command, then a slow scroll.
#include <cstdio>
#include <cstdlib>
#include "DisplayConnectionLinux.h"
#include "OutputPinLinux.h"
#include "InputPinLinux.h"
#include "SSD1331.h"

#include <unistd.h>
#define PRINT std::printf
#define DELAY_MS(ms) usleep((ms) * 1000u)

static uint8_t frameBuffer[96 * 64 * 2];

static void run(SSD1331Full<DisplayConnectionLinux>& oled) {
    typedef SSD1331Full<DisplayConnectionLinux> Chip;
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

int main() {
    const char* chip_path = getenv("GPIO_CHIP");
    if (!chip_path) chip_path = "/dev/gpiochip0";


    OutputPinLinux dc(chip_path, 25);                                                  // Create D/C# pin, (chip_path, offset=25)
    OutputPinLinux res(chip_path, 17, true);                                           // Create RES# pin, (chip_path, offset=17, initial_high=false)
    DisplayConnectionLinux connection(0, 0, &dc, &res, nullptr);                         // Create display connection, (bus=0, device=0, dc, res, busy, wireMode=kSimplex, max_speed_hz=4000000)
    SSD1331Full<DisplayConnectionLinux> oled(connection, frameBuffer);  // Create SSD1331 driver, (connection, buffer)
    if (!oled.valid()) {
        PRINT("SSD1331 init failed (check wiring)\n");
        return 1;
    }
    run(oled);
    return 0;
}
