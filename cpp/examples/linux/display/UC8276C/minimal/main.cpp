// UC8276C minimal example: Waveshare 4.2inch e-Paper (B) on a Raspberry Pi (Linux GCC).
// Wiring: DIN=GPIO10 (SPI0 MOSI), CLK=GPIO11, CS=GPIO8 (CE0), DC=GPIO25, RST=GPIO17, BUSY=GPIO24.
// Each refresh blocks for about 17 s.
#include <cstdio>
#include <cstdlib>
#include "DisplayConnectionLinux.h"
#include "OutputPinLinux.h"
#include "InputPinLinux.h"
#include "UC8276C.h"

static uint8_t bwBuffer[15000];
static uint8_t redBuffer[15000];

int main() {
    const char* chip_path = getenv("GPIO_CHIP");
    if (!chip_path) chip_path = "/dev/gpiochip0";


    OutputPinLinux dc(chip_path, 25);                                                  // Create D/C# pin, (chip_path, offset=25)
    OutputPinLinux res(chip_path, 17, true);                                           // Create RES# pin, (chip_path, offset=17, initial_high=false)
    InputPinLinux  busy(chip_path, 24);                                                // Create BUSY_N pin, (chip_path, offset=24)
    DisplayConnectionLinux connection(0, 0, &dc, &res, &busy);                         // Create display connection, (bus=0, device=0, dc, res, busy, wireMode=kSimplex, max_speed_hz=4000000)
    UC8276CMinimal<DisplayConnectionLinux> epd(connection, bwBuffer, redBuffer);       // Create UC8276C driver, (connection, bw, red, width=400, height=300, busy_timeout_ms=30000)
    if (!epd.valid()) {
        std::printf("UC8276C init failed: BUSY_N never went high\n");
        return 1;
    }
    typedef UC8276CMinimal<DisplayConnectionLinux> Epd;
    epd.clear(Epd::WHITE);                                                             // Fill framebuffer, (color=WHITE)
    epd.set_pixel(10, 10, Epd::BLACK);                                                 // Set pixel, (x, y, color=BLACK)
    for (int16_t x = 20; x < 120; x++) {
        epd.set_pixel(x, 40, Epd::RED);                                                // Set pixel, (x, y, color=BLACK)
    }
    epd.show();                                                                        // Update display (about 17 s), () → bool
    epd.sleep();                                                                       // Enter deep sleep, () → bool
    return 0;
}
