#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <math.h>
#include "I2CConnectionZephyr.h"
#include "BMP384.h"

#ifndef BMP384_I2C_NODE
#define BMP384_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef BMP384_ADDR
#define BMP384_ADDR 0x76
#endif

int main(void) {
    const struct device *dev = DEVICE_DT_GET(BMP384_I2C_NODE);
    I2CConnectionZephyr connection(dev, BMP384_ADDR);
    BMP384Full bmp(connection);                             // Create BMP384 driver, (connection, spi=false)

    // --- Configure for noise-sensitive altitude logging ---
    // osr_p=×16 gives ~12 cm noise-equivalent altitude resolution; the IIR
    // coefficient 3 suppresses door-slam / gust spikes without too much step lag.
    // ODR=25 Hz gives us a sample every 40 ms, well above the ~38 ms T_conv.
    bmp.configure(4, 1, 2, 0x03);                          // Configure ADC and IIR filter, (osr_p 0–5, osr_t 0–5, iir_filter 0–7, odr_sel 0x00–0x11) → None
    bmp.set_mode(BMP384Full.MODE_NORMAL);                   // Set power mode, (mode 0/1/3) → None

    // --- Sample for 30 seconds, logging altitude every 500 ms ---
    // P0 = 1013.25 hPa (ISA sea-level reference). 30 s × 2 Hz = 60 rows.
    const float SEA_LEVEL_HPA = 1013.25f;
    const int DURATION_MS = 30000;
    const int PERIOD_MS = 500;
    int64_t start_ms = k_uptime_get();
    int64_t next_ms = start_ms;
    unsigned int rows = 0;
    while (k_uptime_get() - start_ms < DURATION_MS) {
        if (k_uptime_get() >= next_ms) {
            float t = bmp.temperature();                    // Read temperature, () → float °C
            float p = bmp.pressure();                       // Read pressure, () → float hPa
            float altitude = 44330.0f * (1.0f - powf(p / SEA_LEVEL_HPA, 1.0f / 5.255f));
            float elapsed = (k_uptime_get() - start_ms) / 1000.0f;
            printk("%.1fs  %.2f hPa  %.1f C  %.1f m\n", elapsed, p, t, altitude);
            rows++;
            next_ms += PERIOD_MS;
        }
        k_sleep(K_MSEC(50));
    }

    printk("Sampled %u rows over 30 s\n", rows);
    printk("===DONE: 0 passed, 0 failed===\n");
    return 0;
}
