#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "HT16K33.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x70;
    I2CConnectionLinux connection(bus, addr);

    HT16K33Full matrix(connection);                      // Create HT16K33 driver, (connection)

    // --- Scrolling pixel, brightness sweep, blink and key matrix on a 16x8 matrix ---
    // A single lit pixel visits all 16 x 8 positions, the brightness is swept from
    // 0 to 15, the display blinks at 2, 1 and 0.5 Hz, then key presses are reported
    // for 20 s via the INT output before standby and wake.
    for (uint8_t com = 0; com < HT16K33Full::COMMONS; com++) {
        for (uint8_t row = 0; row < HT16K33Full::ROWS; row++) {
            matrix.clear();                               // Blank the display, () → void
            matrix.set_pixel(com, row);                   // Set one LED, (com 0–7, row 0–15, on) → bool
            matrix.show();                                // Write the shadow RAM, () → void
            usleep((50) * 1000);
        }
    }

    static const uint8_t all_on[16] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
                                       0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};
    matrix.write_raw(all_on, 16, 0);                      // All LEDs on, (data, len, address) → bool
    for (uint8_t level = 0; level < 16; level++) {
        matrix.set_brightness(level);                     // Sweep dimming, (level 0–15) → void
        usleep((100) * 1000);
    }

    static const float blink_hz[4] = {2.0f, 1.0f, 0.5f, 0.0f};
    for (uint8_t i = 0; i < 4; i++) {
        matrix.set_blink(blink_hz[i]);                    // Set blink frequency, (0, 0.5, 1, 2 Hz) → bool
        usleep((3000) * 1000);
    }

    matrix.set_int_output(true);                          // INT output, active low, (enable, active_high) → void
    for (uint16_t t = 0; t < 1000; t++) {                 // 1000 x 20 ms = 20 s
        uint64_t keys = matrix.poll_interrupt();          // Read keys if pending, () → uint64_t bitmask
        if (keys) printf("keys: 0x%x\n", (unsigned)(keys));
        usleep((20) * 1000);
    }

    matrix.standby();                                     // Oscillator off, () → void
    usleep((1000) * 1000);
    matrix.wake();                                        // Oscillator on, state restored, () → void

    return 0;
}
