#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x5C
#endif

#include <cstdio>
#include <cmath>
#include <chrono>
#include <thread>
#include "I2CConnectionLinux.h"
#include "LPS28DFW.h"

int main() {
    I2CConnectionLinux connection(TEST_I2C_BUS, TEST_ADDR);

    // --- High-resolution depth/altitude logger: Mode 1, 64-sample average, 25 Hz ---
    // 64× averaging achieves ~1.1 Pa rms noise; Mode 1 keeps full 0.244 Pa resolution.
    LPS28DFWFull lps(connection);                               // Create LPS28DFW driver, (connection)
    lps.configure(LPS28DFWFull::ODR_25_HZ, LPS28DFWFull::AVG_64,
                  LPS28DFWFull::FS_MODE_1, 1, LPS28DFWFull::LFPF_ODR_OVER_4);  // Configure chip, (odr=25 Hz, avg=64, fs_mode=1, lpf_en=true, lpf_cfg=ODR/4) → void

    // --- Sample every 500 ms for 30 s; report pressure, temperature, altitude ---
    // Sea-level reference uses the ISA standard (1013.25 hPa).
    const auto DURATION = std::chrono::seconds(30);
    const auto PERIOD  = std::chrono::milliseconds(500);
    auto start = std::chrono::steady_clock::now();
    auto next  = start;
    unsigned int samples = 0;
    while (std::chrono::steady_clock::now() - start < DURATION) {
        auto now = std::chrono::steady_clock::now();
        if (now >= next) {
            float p = 0.0f, t = 0.0f;
            lps.read(p, t);                                     // Read both values, (pressure, temperature) → void
            float alt = 44330.0f * (1.0f - std::pow(p / 1013.25f, 1.0f / 5.255f));
            float elapsed = std::chrono::duration<float>(now - start).count();
            printf("%.1fs  %.2f hPa  %.2f C  %.1f m\n", elapsed, p, t, alt);
            samples++;
            next += PERIOD;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }

    printf("Total samples: %u\n", samples);
    printf("===DONE: 0 passed, 0 failed===\n");
    return 0;
}
