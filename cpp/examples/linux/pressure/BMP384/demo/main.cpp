#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x76
#endif

#include <cstdio>
#include <cmath>
#include <chrono>
#include <thread>
#include "I2CConnectionLinux.h"
#include "BMP384.h"

int main() {
    I2CConnectionLinux connection(TEST_I2C_BUS, TEST_ADDR);
    BMP384Full bmp(connection, /*spi=*/false);             // Create BMP384 driver, (connection, spi=false)

    // --- Configure for noise-sensitive altitude logging ---
    // osr_p=×16 gives ~12 cm noise-equivalent altitude resolution; the IIR
    // coefficient 3 suppresses door-slam / gust spikes without too much step lag.
    // ODR=25 Hz gives us a sample every 40 ms, well above the ~38 ms T_conv.
    bmp.configure(4, 1, 2, 0x03);                          // Configure ADC and IIR filter, (osr_p 0–5, osr_t 0–5, iir_filter 0–7, odr_sel 0x00–0x11) → None
    bmp.set_mode(BMP384Full.MODE_NORMAL);                   // Set power mode, (mode 0/1/3) → None

    // --- Sample for 30 seconds, logging altitude every 500 ms ---
    // P0 = 1013.25 hPa (ISA sea-level reference). 30 s × 2 Hz = 60 rows.
    const float SEA_LEVEL_HPA = 1013.25f;
    const auto DURATION = std::chrono::seconds(30);
    const auto PERIOD  = std::chrono::milliseconds(500);
    auto start = std::chrono::steady_clock::now();
    auto next  = start;
    unsigned int rows = 0;
    while (std::chrono::steady_clock::now() - start < DURATION) {
        auto now = std::chrono::steady_clock::now();
        if (now >= next) {
            float t = bmp.temperature();                    // Read temperature, () → float °C
            float p = bmp.pressure();                       // Read pressure, () → float hPa
            float altitude = 44330.0f * (1.0f - std::pow(p / SEA_LEVEL_HPA, 1.0f / 5.255f));
            float elapsed_s = std::chrono::duration<float>(now - start).count();
            printf("%.1fs  %.2f hPa  %.1f C  %.1f m\n", elapsed_s, p, t, altitude);
            rows++;
            next += PERIOD;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }

    printf("Sampled %u rows over 30 s\n", rows);
    printf("===DONE: 0 passed, 0 failed===\n");
    return 0;
}
