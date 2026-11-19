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

    stdio_init_all();
    sleep_ms(2000);

    float a0, b1, b2, c12;
    baro.coefficients(a0, b1, b2, c12);                   // Read cached coefficients, (a0&, b1&, b2&, c12&) → void
    baro.reload_coefficients();                           // Re-read coefficient block, () → void
                                                              // use after a power cycle
    float pa, tc;
    baro.read(pa, tc);                                    // One conversion, (pressure_pa&, temperature_c&) → void
    uint16_t padc, tadc;
    baro.read_raw(padc, tadc);                            // One conversion, (padc&, tadc&) → void, 10-bit counts
    float p = baro.compensate(padc, tadc);                // Compensate raw counts, (padc, tadc) → float Pa
                                                              // pure function, no bus access
    float hpa = baro.pressure_hpa();                      // Read pressure, () → float hPa
    float alt = baro.altitude();                          // Compute altitude, (sea_level_pa=101325.0) → float m
                                                              // barometric formula, informative
    printf("a0=%.3f b1=%.5f b2=%.5f c12=%.8f\n", a0, b1, b2, c12);
    printf("%.0f Pa, %.1f C, raw=(%d, %d), %.0f Pa, %.2f hPa, alt=%.1f m\n", pa, tc, (int)padc, (int)tadc, p, hpa, alt);

    return 0;
}
