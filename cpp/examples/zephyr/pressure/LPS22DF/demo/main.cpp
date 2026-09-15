#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "LPS22DF.h"

#ifndef LPS22DF_I2C_NODE
#define LPS22DF_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef LPS22DF_ADDR
#define LPS22DF_ADDR 0x5C
#endif

int main(void) {
    const struct device *dev = DEVICE_DT_GET(LPS22DF_I2C_NODE);
    I2CConnectionZephyr connection(dev, LPS22DF_ADDR);

    // --- Indoor altimeter preset: 25 Hz, 4-sample average, low-pass filter ---
    // Low-pass at ODR/9 smooths short-term pressure noise (door slams, fans);
    // 4-sample averaging trims noise without adding visible lag.
    LPS22DFFull lps(connection);                              // Create LPS22DF driver, (connection, spi=false)
    lps.configure(4, 0, true, 1, true);                       // Configure chip, (odr=25 Hz, avg=4, en_lpfp=true, lfpf_cfg=ODR/9, bdu=true) → None

    // --- Baseline capture: 2-second stabilization then zero the altimeter ---
    k_sleep(K_MSEC(2000));
    float baseline_p = lps.pressure();                        // Read pressure, () → float Pa
    printk("Baseline: %.0f Pa\n", baseline_p);

    float pmin = baseline_p, pmax = baseline_p, psum = 0;
    float tmin = 0, tmax = 0, tsum = 0;
    float dmin = 0, dmax = 0, dsum = 0;
    for (int n = 0; n < 30; n++) {
        float p = lps.pressure();                             // Read pressure, () → float Pa
        float t = lps.temperature();                          // Read temperature, () → float °C
        float d = lps.altitude(baseline_p);                   // Compute altitude, (sea_level_pa=baseline_p) → float m
        if (n == 0) { tmin = t; tmax = t; dmin = d; dmax = d; }
        if (p < pmin) pmin = p;
        if (p > pmax) pmax = p;
        psum += p;
        if (t < tmin) tmin = t;
        if (t > tmax) tmax = t;
        tsum += t;
        if (d < dmin) dmin = d;
        if (d > dmax) dmax = d;
        dsum += d;
        printk("%ds: %.0f Pa, T=%.2f C, dalt=%.3f m\n", n, p, t, d);
        k_sleep(K_SECONDS(1));
    }
    printk("P min=%.0f max=%.0f mean=%.1f Pa\n", pmin, pmax, psum / 30);
    printk("T min=%.2f max=%.2f mean=%.2f C\n", tmin, tmax, tsum / 30);
    printk("dalt min=%.3f max=%.3f mean=%.3f m\n", dmin, dmax, dsum / 30);
    printk("===DONE: 0 passed, 0 failed===\n");
    return 0;
}