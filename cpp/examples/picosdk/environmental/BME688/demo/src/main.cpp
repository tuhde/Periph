#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "BME688.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x77);
    BME688Full bme(connection);

    stdio_init_all();
    sleep_ms(2000);

    // --- Room air probe with parallel mode ---
    // Part 1: ten forced-mode readings (T, RH, hPa, Ohm, dew point), one per 5 s.
    // Part 2: parallel mode with a 3-step heater profile (320, 250 and 180 degC;
    // repeats 1, 2, 1; shared wait 100 ms) for one minute, printing the gas
    // resistance of every heater step, then back to sleep.
    bme.configure(BME688Full::OSRS_X2, BME688Full::OSRS_X16, BME688Full::OSRS_X1, BME688Full::MODE_SLEEP, BME688Full::FILTER_15);  // Configure chip, (osrs_t=×2, osrs_p=×16, osrs_h=×1, mode=sleep, filter=15) → void
    bme.set_heater(320, 150);                           // Configure heater profile 0, (temp_c=320, duration_ms=150) → void

    printf("--- forced mode ---\n");
    for (int n = 0; n < 10; n++) {
        float t, p, h, g;
        bme.read_all(t, p, h, g);                       // Read all sensors in one cycle, (t, p, h, g) → void
        float gm = logf((h > 0.01f ? h : 0.01f) / 100.0f) + 17.62f * t / (243.12f + t);
        float dew = 243.12f * gm / (17.62f - gm);       // Magnus dew point, °C
        printf("%d: %.1f C, %.1f %%RH, %.1f hPa, %.0f Ohm, dew %.1f C\n", n, (double)t, (double)h, (double)p, (double)g, (double)dew);
        sleep_ms(5000);
    }

    printf("--- parallel mode ---\n");
    const int16_t temps[3] = {320, 250, 180};
    const uint8_t repeats[3] = {1, 2, 1};
    bme.start_parallel(temps, repeats, 3, 100.0f);      // Start parallel mode, (temps, repeats, steps=3, shared_wait_ms=100) → void
    for (int n = 0; n < 60; n++) {
        BME688Field f[3];
        uint8_t cnt = bme.read_parallel(f, 3);          // Read the new data fields, (out, max) → uint8_t count
        for (uint8_t i = 0; i < cnt; i++) {
            printf("step %d #%d: %.1f C, %.1f %%RH, %.0f Ohm (valid=%d, stable=%d)\n", f[i].step, f[i].sub_index, (double)f[i].temperature, (double)f[i].humidity, (double)f[i].gas_resistance, f[i].gas_valid, f[i].heater_stable);
        }
        sleep_ms(1000);
    }
    bme.stop_parallel();                                // Back to sleep, () → void

    printf("===DONE: 0 passed, 0 failed===\n");
    while (true) {
    sleep_ms(1000); 
        sleep_ms(10);
    }

    return 0;
}
