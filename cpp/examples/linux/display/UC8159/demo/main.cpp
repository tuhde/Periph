// UC8159 demo example: Waveshare 5.65inch e-Paper (F) on a Raspberry Pi (Linux GCC).
// Wiring: DIN=GPIO10 (SPI0 MOSI), CLK=GPIO11, CS=GPIO8 (CE0), DC=GPIO25, RST=GPIO17, BUSY=GPIO24.
// Each refresh blocks for about 30 s.
#include <cstdio>
#include <cstdlib>
#include <ctime>
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
    UC8159Full<DisplayConnectionLinux> epd(connection, frameBuffer, sizeof(frameBuffer));          // Create UC8159 driver, (connection, buffer, buffer_len, busy_timeout_ms=60000)
    if (!epd.valid()) {
        std::printf("UC8159 init failed: BUSY never went high\n");
        return 1;
    }
    typedef UC8159Full<DisplayConnectionLinux> Epd;

    // --- Compose the colour chart in the framebuffer ---
    // White page, seven vertical bars (black, white, green, blue, red, yellow, orange) and a
    // one-pixel black frame inset by 4 px (a frame on the very edge can hide behind the bezel).
    epd.clear(Epd::WHITE);                                                             // Fill framebuffer, (color=WHITE)
    for (uint8_t c = 0; c < 7; c++) {
        epd.draw_rect(20 + c * 80, 40, 80, 368, c, true);                              // Draw rectangle, (x, y, w, h, color, fill)
    }
    epd.draw_rect(4, 4, 592, 440, Epd::BLACK);                                         // Draw rectangle, (x, y, w, h, color, fill=false)

    // --- Refresh and time it ---
    // show() blocks for the whole DTM1/PON/DRF/POF sequence: about 30 s.
    uint32_t t0 = NOW_MS();
    bool ok = epd.show();                                                              // Update display, () → bool
    uint32_t elapsed = NOW_MS() - t0;
    PRINT("%s after %lu ms\n", ok ? "refresh took" : "BUSY timeout", (unsigned long)elapsed);

    // --- Save the image and power down ---
    // The panel keeps the picture without power; deep sleep stops the controller drawing current.
    epd.sleep();                                                                       // Enter deep sleep, () → bool
    return 0;
}
