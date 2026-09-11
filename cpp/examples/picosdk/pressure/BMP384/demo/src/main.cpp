#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "BMP384.h"

int main(void) {
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x76);
    BMP384Full bmp(connection, /*spi=*/false);             // Create BMP384 driver, (connection, spi=false)

    stdio_init_all();
    sleep_ms(2000);

    // --- Configure for noise-sensitive altitude logging ---
    // osr_p=×16 gives ~12 cm noise-equivalent altitude resolution; the IIR
    // coefficient 3 suppresses door-slam / gust spikes without too much step lag.
    // ODR=25 Hz gives us a sample every 40 ms, well above the ~38 ms T_conv.
    bmp.configure(4, 1, 2, 0x03);                          // Configure ADC and IIR filter, (osr_p 0–5, osr_t 0–5, iir_filter 0–7, odr_sel 0x00–0x11) → None
    bmp.set_mode(BMP384Full.MODE_NORMAL);                   // Set power mode, (mode 0/1/3) → None

    // --- Sample for 30 seconds, logging altitude every 500 ms ---
    // P0 = 1013.25 hPa (ISA sea-level reference). 30 s × 2 Hz = 60 rows.
    const float SEA_LEVEL_HPA = 1013.25f;
    const uint32_t PERIOD_MS = 500;
    const uint32_t DURATION_MS = 30000;
    absolute_time_t start = get_absolute_time();
    absolute_time_t next = start;
    unsigned int rows = 0;
    while (absolute_time_diff_us(start, get_absolute_time()) < (int64_t)DURATION_MS * 1000) {
        if (absolute_time_diff_us(next, get_absolute_time()) <= 0) {
            float t = bmp.temperature();                    // Read temperature, () → float °C
            float p = bmp.pressure();                       // Read pressure, () → float hPa
            float altitude = 44330.0f * (1.0f - powf(p / SEA_LEVEL_HPA, 1.0f / 5.255f));
            float elapsed_s = (float)absolute_time_diff_us(start, get_absolute_time()) / 1.0e6f;
            printf("%.1fs  %.2f hPa  %.1f C  %.1f m\n", elapsed_s, p, t, altitude);
            rows++;
            next = delayed_by_ms(next, PERIOD_MS);
        }
        sleep_ms(50);
    }

    printf("Sampled %u rows over 30 s\n", rows);
    printf("===DONE: 0 passed, 0 failed===\n");
    while (true) sleep_ms(1000);
    return 0;
}
