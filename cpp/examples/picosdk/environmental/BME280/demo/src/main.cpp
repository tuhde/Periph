#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "BME280.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x76);
    BME280Full bme(connection, /*spi=*/false);

    stdio_init_all();
    sleep_ms(2000);

    // --- Weather monitoring preset: forced mode, ×1/×1/×1, filter off ---
    // BME280 datasheet "weather monitoring" preset: minimum power,
    // single-shot, 8 ms typ / 9.3 ms max per cycle. Sleep between samples
    // to demonstrate battery-friendly indoor monitoring.
    bme.configure(BME280Full::OSRS_X1, BME280Full::OSRS_X1, BME280Full::OSRS_X1, BME280Full::MODE_FORCED, BME280Full::FILTER_OFF, BME280Full::T_SB_0_5_MS);  // Configure chip, (osrs_t=×1, osrs_p=×1, osrs_h=×1, mode=forced, filter=off, t_sb=0) → void

    float t_min = 999, t_max = -999, t_sum = 0;
    float h_min = 999, h_max = -999, h_sum = 0;
    float p_min = 1e9, p_max = -1e9, p_sum = 0;
    int n_samples = 10;

    for (int n = 0; n < n_samples; n++) {
        float t = bme.temperature();                    // Read temperature, () → float °C
        float p = bme.pressure();                       // Read pressure, () → float hPa
        float h = bme.humidity();                       // Read humidity, () → float %RH
        float a = bme.altitude();                       // Compute altitude, (sea_level_hpa=1013.25) → float m
        float d = bme.dew_point();                      // Compute dew point, () → float °C
        if (t < t_min) t_min = t; if (t > t_max) t_max = t; t_sum += t;
        if (h < h_min) h_min = h; if (h > h_max) h_max = h; h_sum += h;
        if (p < p_min) p_min = p; if (p > p_max) p_max = p; p_sum += p;
        printf("%d", n);
        printf(": ");
        printf("%.1f", t); printf(" C, ");
        printf("%.1f", h); printf(" %RH, ");
        printf("%.1f", p); printf(" hPa, dew=");
        printf("%.1f", d); printf(" C, alt=");
        printf("%.1f", a); printf(" m\n");
        sleep_ms(1000);
    }

    // --- Half-way: breathe gently on the sensor for 3 seconds ---
    // User exposes the sensor to humid exhaled air; humidity climbs from
    // ~40 %RH toward ~80 %RH, dew point spikes toward ambient temperature,
    // pressure stays flat, temperature rises only slightly. Demonstrates
    // the humidity channel's response and the dew-point alarm use case.
    printf("--- Breathe gently on the sensor for 3 seconds ---\n");
    sleep_ms(3000);
    {
        float t = bme.temperature();                    // Read temperature, () → float °C
        float p = bme.pressure();                       // Read pressure, () → float hPa
        float h = bme.humidity();                       // Read humidity, () → float %RH
        float d = bme.dew_point();                      // Compute dew point, () → float °C
        if (t < t_min) t_min = t; if (t > t_max) t_max = t; t_sum += t;
        if (h < h_min) h_min = h; if (h > h_max) h_max = h; h_sum += h;
        if (p < p_min) p_min = p; if (p > p_max) p_max = p; p_sum += p;
        printf("after breath: ");
        printf("%.1f", t); printf(" C, ");
        printf("%.1f", h); printf(" %RH, ");
        printf("%.1f", p); printf(" hPa, dew=");
        printf("%.1f", d); printf(" C\n");
        n_samples++;
    }

    printf("T: "); printf("%.1f", t_min); printf("/");
    printf("%.1f", t_sum / n_samples); printf("/");
    printf("%.1f", t_max); printf(" C\n");
    printf("RH: "); printf("%.1f", h_min); printf("/");
    printf("%.1f", h_sum / n_samples); printf("/");
    printf("%.1f", h_max); printf(" %\n");
    printf("P: "); printf("%.1f", p_min); printf("/");
    printf("%.1f", p_sum / n_samples); printf("/");
    printf("%.1f", p_max); printf(" hPa\n");

    printf("===DONE: 0 passed, 0 failed===\n");
    while (true) {
    sleep_ms(1000); 
        sleep_ms(10);
    }

    return 0;
}
