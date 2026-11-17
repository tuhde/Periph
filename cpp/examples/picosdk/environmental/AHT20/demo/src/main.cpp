#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "AHT20.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x38);
    AHT20Full aht(connection);

    stdio_init_all();

    // --- Verify calibration before starting the logging session ---
    // Most AHT20 modules ship pre-calibrated; if the CAL bit is not set
    // the driver already sent the calibration init sequence during construction.
    printf("Calibrated: ");
    printf("%d\n", aht.is_calibrated());                               // Check calibration status, () → bool

    printf("Time     T (C)      RH (%)     Dew (C)\n");
    while (true) {

    // --- Each reading requires an 80 ms measurement cycle ---
    // The sensor cannot output data faster than this; the driver
    // handles the trigger + wait internally.
    float t, h;
    bool read_ok = aht.read(t, h);                             // Trigger measurement, (temperature_c, humidity_pct) → bool
    if (!read_ok) {
        printf("Read timeout\n");
        sleep_ms(5000);
        continue;
    }

    // --- Magnus formula dew-point approximation ---
    // gamma = ln(RH/100) + (17.625 * T) / (243.04 + T)
    // dew_point = (243.04 * gamma) / (17.625 - gamma)
    // Accurate to ±0.5 °C for 0 < T < 60 °C and 1 < RH < 100 %RH.
    float gamma = log(h / 100.0f) + (17.625f * t) / (243.04f + t);
    float dew   = (243.04f * gamma) / (17.625f - gamma);

    static unsigned long n = 0;
    printf("%d", n);           printf("       ");
    printf("%.2f", t);        printf("     ");
    printf("%.2f", h);        printf("     ");
    printf("%.2f\n", dew);
    n++;

    sleep_ms(5000);
        sleep_ms(10);
    }

    return 0;
}
