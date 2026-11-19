#include <cstdio>
#include <cstdint>
#include <math.h>
#include <time.h>
#include "I2CConnectionLinux.h"
#include "Lps22hb.h"

#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x5C
#endif

static int64_t uptime_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

int main() {
    I2CConnectionLinux connection(TEST_I2C_BUS, TEST_ADDR);

    // --- Initialization and configuration for altimeter preset ---
    // ODR=10 Hz gives ~10 Hz pressure output; BDU=1 latches the output
    // registers so a coherent 24-bit pressure can be read without tearing;
    // EN_LPFP=1 with LPFP_BW_ODR_20 (LPFP_CFG=1) gives an additional
    // ODR/20 low-pass filter that suppresses the kind of cabin-air
    // pressure bursts that would otherwise read as bogus altitude steps.
    LPS22HBFull lps(connection);                           // Create LPS22HB driver, (connection)
    lps.configure(LPS22HBFull::ODR_10_HZ, true, true, LPS22HBFull::LPFP_BW_ODR_20, false, false);  // Configure chip, (odr=10Hz, bdu=true, en_lpfp=true, lpfp_cfg=ODR/20, lc_en=false, sim=false) → None
    lps.reset_lpf();                                      // Flush transitory LPF state after enabling EN_LPFP, () → None

    // --- Main loop: poll P_DA rather than fixed delay ---
    // The chip updates pressure asynchronously at 10 Hz; spinning on the
    // STATUS register's P_DA bit lets us sample fresh data immediately
    // rather than racing the ODR clock with sleep().
    const float sea_level_Pa = 101325.0f;
    int64_t last_print = 0;
    int64_t last_autozero = 0;
    int64_t t0 = uptime_ms();

    while (uptime_ms() - t0 < 60000) {
        float p_Pa = lps.pressure();                      // Read pressure, () → float Pa
                                                        // waits for STATUS.P_DA before reading PRESS_XL..PRESS_H
        float t_C = lps.temperature();                    // Read temperature, () → float °C
        int64_t now = uptime_ms();

        if (now - last_print >= 1000) {
            last_print = now;
            // --- Altitude via the barometric formula ---
            // The 44330 × (1 − (p/p0)^(1/5.255)) approximation is valid up
            // to ~11000 m and troposphere temperatures; for higher
            // altitudes use the full hypsometric equation.
            float altitude_m = 44330.0f * (1.0f - powf(p_Pa / sea_level_Pa, 1.0f / 5.255f));  // Barometric altitude, () → float m
            printf("%llds: alt=%.2f m, T=%.2f C\n", (long long)(now / 1000), altitude_m, t_C);
        }

        // --- AUTOZERO removes atmospheric drift every 10 s ---
        // Weather fronts shift sea-level pressure by ~1 hPa/hour, which
        // would otherwise show up as bogus altitude drift in a relative
        // (uncalibrated) altimeter; re-zeroing REF_P every 10 s cancels
        // that slow DC bias without throwing away the 10 Hz rate.
        if (now - last_autozero >= 10000) {
            last_autozero = now;
            lps.set_autozero();                           // Set AUTOZERO, () → None
                                                        // current pressure is stored in REF_P
            printf("Reference updated.\n");
        }
    }

    printf("===DONE: 0 passed, 0 failed===\n");
    return 0;
}