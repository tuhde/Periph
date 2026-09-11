#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "LPS22DF.h"

int main(void) {
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x5C);

    // --- Indoor altimeter preset: 25 Hz, 4-sample average, low-pass filter ---
    // Low-pass at ODR/9 smooths short-term pressure noise (door slams, fans);
    // 4-sample averaging trims noise without adding visible lag.
    LPS22DFFull lps(connection, /*spi=*/false);            // Create LPS22DF driver, (connection, spi=false)
    lps.configure(4, 0, true, 1, true);                       // Configure chip, (odr=25 Hz, avg=4, en_lpfp=true, lfpf_cfg=ODR/9, bdu=true) → None

    stdio_init_all();
    sleep_ms(2000);

    // --- Baseline capture: 2-second stabilization then zero the altimeter ---
    sleep_ms(2000);
    float baseline_p = lps.pressure();                        // Read pressure, () → float Pa
    printf("Baseline: %.0f Pa\n", baseline_p);

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
        printf("%ds: %.0f Pa, T=%.2f C, dalt=%.3f m\n", n, p, t, d);
        sleep_ms(1000);
    }
    printf("P min=%.0f max=%.0f mean=%.1f Pa\n", pmin, pmax, psum / 30);
    printf("T min=%.2f max=%.2f mean=%.2f C\n", tmin, tmax, tsum / 30);
    printf("dalt min=%.3f max=%.3f mean=%.3f m\n", dmin, dmax, dsum / 30);
    printf("===DONE: 0 passed, 0 failed===\n");
    while (true) sleep_ms(1000);
    return 0;
}