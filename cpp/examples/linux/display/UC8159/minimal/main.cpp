// UC8159 minimal example: Waveshare 5.65inch e-Paper (F) on a Raspberry Pi (Linux GCC).
// Wiring: DIN=GPIO10 (SPI0 MOSI), CLK=GPIO11, CS=GPIO8 (CE0), DC=GPIO25, RST=GPIO17, BUSY=GPIO24.
// Each refresh blocks for about 30 s.
#include <cstdio>
#include <cstdlib>
#include "DisplayConnectionLinux.h"
#include "OutputPinLinux.h"
#include "InputPinLinux.h"
#include "UC8159.h"
#include <time.h>
#define PRINT std::printf
static inline uint32_t NOW_MS() { struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts); return (uint32_t)(ts.tv_sec * 1000u + ts.tv_nsec / 1000000u); }

static uint8_t frameBuffer[100801];

int main() {
    const char* chip_path = getenv("GPIO_CHIP");
    if (!chip_path) chip_path = "/dev/gpiochip0";


    OutputPinLinux dc(chip_path, 25);                                                  // Create D/C# pin, (chip_path, offset=25)
    OutputPinLinux res(chip_path, 17, true);                                           // Create RES# pin, (chip_path, offset=17, initial_high=false)
    InputPinLinux  busy(chip_path, 24);                                                // Create BUSY pin, (chip_path, offset=24)
    DisplayConnectionLinux connection(0, 0, &dc, &res, &busy);                         // Create display connection, (bus=0, device=0, dc, res, busy, wireMode=kSimplex, max_speed_hz=4000000)
    UC8159Minimal<DisplayConnectionLinux> epd(connection, frameBuffer, sizeof(frameBuffer));       // Create UC8159 driver, (connection, buffer, buffer_len, busy_timeout_ms=60000)
    if (!epd.valid()) {
        std::printf("UC8159 init failed: BUSY never went high\n");
        return 1;
    }
    typedef UC8159Minimal<DisplayConnectionLinux> Epd;
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
    return 0;
}
