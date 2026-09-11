#include <stdio.h>
#include <math.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "BMP581.h"

int main(void) {
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x46);

    stdio_init_all();
    sleep_ms(2000);

    BMP581Full bmp(connection, /*spi=*/false);

    // --- Precision altimeter: 10 Hz NORMAL mode for 30 seconds ---
    bmp.configure(0x17, BMP581Full::OSR_16X, BMP581Full::OSR_4X, true);  // Configure chip, (odr=10Hz, osr_p=×16, osr_t=×4, press_en) → None

    float pressures[300], temps[300], alts[300];
    for (int n = 0; n < 300; n++) {
        pressures[n] = bmp.pressure();                    // Read pressure, () → float Pa
        temps[n] = bmp.temperature();                     // Read temperature, () → float °C
        alts[n] = bmp.altitude();                         // Compute altitude, (sea_level_pa=101325.0) → float m
        sleep_ms(100);
    }

    float amin = alts[0], amax = alts[0];
    for (int n = 1; n < 300; n++) {
        if (alts[n] < amin) amin = alts[n];
        if (alts[n] > amax) amax = alts[n];
    }
    printf("Bypass: alt min=%.3f max=%.3f spread=%.3f m\n", amin, amax, amax - amin);

    // --- Compare IIR bypass vs IIR coefficient 3 noise floor ---
    bmp.set_iir_filter(BMP581Full::IIR_COEFF_3, BMP581Full::IIR_BYPASS);  // Set IIR filter, (coeff_p=7-tap, coeff_t=bypass) → None

    float alts2[300];
    for (int n = 0; n < 300; n++) {
        bmp.pressure();                                   // Read pressure, () → float Pa
        alts2[n] = bmp.altitude();                        // Compute altitude, (sea_level_pa=101325.0) → float m
        sleep_ms(100);
    }
    float amin2 = alts2[0], amax2 = alts2[0];
    for (int n = 1; n < 300; n++) {
        if (alts2[n] < amin2) amin2 = alts2[n];
        if (alts2[n] > amax2) amax2 = alts2[n];
    }
    printf("IIR=3:  alt min=%.3f max=%.3f spread=%.3f m\n", amin2, amax2, amax2 - amin2);

    printf("===DONE: 0 passed, 0 failed===\n");
    while (true) sleep_ms(1000);

    return 0;
}