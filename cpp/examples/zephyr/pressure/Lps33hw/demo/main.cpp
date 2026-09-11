#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <math.h>
#include "I2CConnectionZephyr.h"
#include "Lps33hw.h"

#ifndef LPS33HW_I2C_NODE
#define LPS33HW_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef LPS33HW_ADDR
#define LPS33HW_ADDR 0x5C
#endif

int main(void) {
    const struct device *dev = DEVICE_DT_GET(LPS33HW_I2C_NODE);
    I2CConnectionZephyr connection(dev, LPS33HW_ADDR);

    // --- Initialization and configuration for altimeter preset ---
    // ODR=10 Hz gives ~10 Hz pressure output; BDU=1 latches the output
    // registers so a coherent 24-bit pressure can be read without tearing;
    // EN_LPFP=1 with LPFP_BW_ODR_20 (LPFP_CFG=1) gives an additional
    // ODR/20 low-pass filter that suppresses the kind of cabin-air
    // pressure bursts that would otherwise read as bogus altitude steps.
    LPS33HWFull lps(connection);                           // Create LPS33HW driver, (connection)
    lps.configure(LPS33HWFull::ODR_10_HZ, true, true, LPS33HWFull::LPFP_BW_ODR_20, false, false);  // Configure chip, (odr=10Hz, bdu=true, en_lpfp=true, lpfp_cfg=ODR/20, lc_en=false, sim=false) → None
    lps.reset_lpf();                                      // Flush transitory LPF state after enabling EN_LPFP, () → None

    // --- Main loop: poll P_DA rather than fixed delay ---
    // The chip updates pressure asynchronously at 10 Hz; spinning on the
    // STATUS register's P_DA bit lets us sample fresh data immediately
    // rather than racing the ODR clock with k_sleep().
    const float sea_level_Pa = 101325.0f;
    int64_t last_print = 0;
    int64_t last_autozero = 0;
    int64_t t0 = k_uptime_get();

    while (k_uptime_get() - t0 < 60000) {
        float p_Pa = lps.pressure();                      // Read pressure, () → float Pa
                                                        // waits for STATUS.P_DA before reading PRESS_XL..PRESS_H
        float t_C = lps.temperature();                    // Read temperature, () → float °C
        int64_t now = k_uptime_get();

        if (now - last_print >= 1000) {
            last_print = now;
            // --- Altitude via the barometric formula ---
            // The 44330 × (1 − (p/p0)^(1/5.255)) approximation is valid up
            // to ~11000 m and troposphere temperatures; for higher
            // altitudes use the full hypsometric equation.
            float altitude_m = 44330.0f * (1.0f - powf(p_Pa / sea_level_Pa, 1.0f / 5.255f));  // Barometric altitude, () → float m
            printk("%llds: alt=%.2f m, T=%.2f C\n", (long long)(now / 1000), altitude_m, t_C);
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
            printk("Reference updated.\n");
        }
    }

    return 0;
}