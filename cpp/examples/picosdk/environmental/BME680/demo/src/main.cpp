#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "BME680.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x77);
    BME680Full bme(connection);

    stdio_init_all();
    sleep_ms(2000);

    // --- Room air quality probe: 4-in-1 sensor polling with VOC event ---
    // Polls all four sensors once every 5 seconds for 5 minutes (60 ticks).
    // At tick 30, the user is prompted to expose the sensor to a VOC source.
    // Gas resistance drops sharply on exposure and recovers over the remaining
    // ticks, demonstrating raw VOC sensitivity without the BSEC library.
    bme.configure(BME680Full::OSRS_X2, BME680Full::OSRS_X16, BME680Full::OSRS_X1, BME680Full::MODE_FORCED, BME680Full::FILTER_15);  // Configure chip, (osrs_t=×2, osrs_p=×16, osrs_h=×1, mode=forced, filter=15) → void
    bme.set_heater(320, 150);                           // Configure heater profile 0, (temp_c=320, duration_ms=150) → void

    float t_min = 999, t_max = -999, t_sum = 0;
    float g_min = 1e12, g_max = 0;
    int gas_count = 0;

    for (int n = 0; n < 60; n++) {
        if (n == 30) {
            printf("--- Expose sensor to VOC source now (alcohol/marker) ---\n");
        }
        float t, p, h, g;
        bme.read_all(t, p, h, g);                       // Read all sensors in one cycle, (t, p, h, g) → void
        if (t < t_min) t_min = t;
        if (t > t_max) t_max = t;
        t_sum += t;
        if (!isnan(g)) {
            if (g < g_min) g_min = g;
            if (g > g_max) g_max = g;
            gas_count++;
        }
        printf("%d", n);
        printf(": ");
        printf("%.1f", t);
        printf(" C, ");
        printf("%.1f", h);
        printf(" %RH, ");
        printf("%.1f", p);
        printf(" hPa, ");
        printf("%.0f", g);
        printf(" Ohm\n");
        sleep_ms(5000);
    }

    float t_avg = t_sum / 60.0f;
    printf("T: ");
    printf("%.1f", t_min);
    printf("/");
    printf("%.1f", t_avg);
    printf("/");
    printf("%.1f", t_max);
    printf(" C\n");
    if (gas_count > 0 && g_min > 0) {
        printf("VOC response ratio: ");
        printf("%.1f", g_max / g_min);
        printf("x\n");
    }

    printf("===DONE: 0 passed, 0 failed===\n");
    while (true) {
    sleep_ms(1000); 
        sleep_ms(10);
    }

    return 0;
}
