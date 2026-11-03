// SSD1331 minimal example: Waveshare 0.95inch RGB OLED, Raspberry Pi (Linux GCC)
// Wiring: DIN=GPIO10 (SPI0 MOSI), CLK=GPIO11, CS=GPIO8 (CE0), DC=GPIO25, RST=GPIO17 (no BUSY line).
// Minimal use: eight colour bars and a white frame, drawn into the framebuffer and sent once.
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

static void run(SSD1331Minimal<DisplayConnectionLinux>& oled) {
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

int main() {
    const char* chip_path = getenv("GPIO_CHIP");
    if (!chip_path) chip_path = "/dev/gpiochip0";


    OutputPinLinux dc(chip_path, 25);                                                  // Create D/C# pin, (chip_path, offset=25)
    OutputPinLinux res(chip_path, 17, true);                                           // Create RES# pin, (chip_path, offset=17, initial_high=false)
    DisplayConnectionLinux connection(0, 0, &dc, &res, nullptr);                         // Create display connection, (bus=0, device=0, dc, res, busy, wireMode=kSimplex, max_speed_hz=4000000)
    SSD1331Minimal<DisplayConnectionLinux> oled(connection, frameBuffer);  // Create SSD1331 driver, (connection, buffer)
    if (!oled.valid()) {
        PRINT("SSD1331 init failed (check wiring)\n");
        return 1;
    }
    run(oled);
    return 0;
}
