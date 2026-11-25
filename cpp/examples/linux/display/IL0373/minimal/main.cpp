// IL0373 minimal example: Waveshare 2.13inch e-Paper (D) on a Raspberry Pi (Linux GCC).
// Wiring: DIN=GPIO10 (SPI0 MOSI), CLK=GPIO11, CS=GPIO8 (CE0), DC=GPIO25, RST=GPIO17, BUSY=GPIO24.
// Each refresh blocks until BUSY goes high (the panel datasheet gives no update time).
#include <cstdio>
#include <cstdlib>
#include "DisplayConnectionLinux.h"
#include "OutputPinLinux.h"
#include "InputPinLinux.h"
#include "IL0373.h"

static uint8_t frameBuffer[2756];

int main() {
    const char* chip_path = getenv("GPIO_CHIP");
    if (!chip_path) chip_path = "/dev/gpiochip0";


    OutputPinLinux dc(chip_path, 25);                                                  // Create D/C# pin, (chip_path, offset=25)
    OutputPinLinux res(chip_path, 17, true);                                           // Create RES# pin, (chip_path, offset=17, initial_high=false)
    InputPinLinux  busy(chip_path, 24);                                                // Create BUSY pin, (chip_path, offset=24)
    DisplayConnectionLinux connection(0, 0, &dc, &res, &busy);                         // Create display connection, (bus=0, device=0, dc, res, busy, wireMode=kSimplex, max_speed_hz=4000000)
    IL0373Minimal<DisplayConnectionLinux> epd(connection, frameBuffer);       // Create IL0373 driver, (connection, buffer, width=104, height=212, busy_timeout_ms=30000)
    if (!epd.valid()) {
        std::printf("IL0373 init failed: BUSY never went high\n");
        return 1;
    }
    typedef IL0373Minimal<DisplayConnectionLinux> Epd;
    epd.clear(Epd::WHITE);                                                             // Fill framebuffer, (color=WHITE)
    epd.set_pixel(10, 10, Epd::BLACK);                                                 // Set pixel, (x, y, color=BLACK)
    for (int16_t x = 20; x < 100; x++) {
        epd.set_pixel(x, 40, Epd::BLACK);                                                // Set pixel, (x, y, color=BLACK)
    }
    epd.show();                                                                        // Update display, () → bool
    epd.sleep();                                                                       // Enter deep sleep, () → bool
    return 0;
}
