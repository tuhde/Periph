#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "MPL115A2.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x60);
    MPL115A2Full baro(connection);                      // Create MPL115A2 driver, (connection)

    static int passed = 0, failed = 0;

    stdio_init_all();
    sleep_ms(2000);

    float a0, b1, b2, c12;
    baro.coefficients(a0, b1, b2, c12);                   // Read cached coefficients, (a0&, b1&, b2&, c12&) → void
    printf("a0=%.3f b1=%.5f b2=%.5f c12=%.8f\n", a0, b1, b2, c12);

    // --- Barometer log ---
    // Reads the pressure every 2 s for 30 s, printing Pa, hPa, approximate
    // altitude and the raw counts, then the min/max of the run.
    float pmin = 1e9f, pmax = 0.0f;
    for (int n = 0; n < 15; n++) {
        float pa = baro.read_pressure();                  // Read pressure, () → float Pa
        float hpa = baro.pressure_hpa();                  // Read pressure, () → float hPa
        float alt = baro.altitude();                      // Compute altitude, (sea_level_pa=101325.0) → float m
        uint16_t padc, tadc;
        baro.read_raw(padc, tadc);                        // One conversion, (padc&, tadc&) → void
        if (pa < pmin) pmin = pa;
        if (pa > pmax) pmax = pa;
        printf("%ds: %.0f Pa, %.2f hPa, alt=%.1f m, raw=(%d, %d)\n", n * 2, pa, hpa, alt, (int)padc, (int)tadc);
        sleep_ms(2000);
    }
    printf("Summary: min=%.0f Pa, max=%.0f Pa\n", pmin, pmax);

    printf("===DONE: ");
    printf("%d", passed);
    printf(" passed, ");
    printf("%d", failed);
    printf(" failed===\n");
    while (true) {
    sleep_ms(1000); 
        sleep_ms(10);
    }

    return 0;
}
