#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "Htu31d.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x40);
    HTU31DFull sensor(connection);                                  // Create HTU31D driver, (connection) → void
    sensor.init();                                                  // Soft reset, () → void

    // --- Condensation-aware hygrometer ---
    // Prints the serial number and the diagnostic byte, reads temperature and
    // humidity with the dew point 15 times, compares the four oversampling
    // settings with their typical conversion time, shows the heater effect for
    // 10 s, demonstrates the non-blocking conversion pair and soft-resets.
    uint32_t serial = 0;
    sensor.serial_number(serial);                                   // Read the serial number, (serial&) → bool
    printf("serial 0x%02X diagnostic 0x%02X\n", (unsigned)(serial), (unsigned)(sensor.diagnostic()));
    float t = 0, rh = 0;
    for (int i = 0; i < 15; i++) {
        sensor.read(t, rh);                                         // Read temperature and humidity, (temperature_c&, humidity_pct&) → bool
        printf("%.2f C %.1f %%RH dew %.2f C\n", (double)(t), (double)(rh), (double)(sensor.dew_point()));
        sleep_ms(2000);
    }
    for (uint8_t osr = 0; osr < 4; osr++) {
        sensor.set_resolution(osr, osr);                            // Set oversampling, (osr_rh, osr_t) → bool
        sensor.read(t, rh);
        printf("osr %lu typical %.2f ms: %.2f C %.1f %%RH\n", (unsigned long)(osr), (double)(sensor.conversion_time() * 1000.0f), (double)(t), (double)(rh));
    }
    sensor.set_resolution(3, 3);
    sensor.read(t, rh);
    printf("before heater %.2f C %.1f %%RH\n", (double)(t), (double)(rh));
    sensor.set_heater(true);                                        // Switch the heater on, (on) → void
    printf("heater on %lu diagnostic 0x%02X\n", (unsigned long)(sensor.is_heater_on()), (unsigned)(sensor.diagnostic()));
    sleep_ms(10000);
    sensor.set_heater(false);                                       // Heater off
    sensor.read(t, rh);
    printf("after heater %.2f C %.1f %%RH\n", (double)(t), (double)(rh));
    sensor.start_conversion();                                      // Start a conversion, () → void
    sleep_ms(30);
    sensor.read_result(t, rh);                                      // Read last conversion result, (temperature_c&, humidity_pct&) → bool
    printf("result %.2f C %.1f %%RH\n", (double)(t), (double)(rh));
    sensor.software_reset();                                        // Soft reset, () → void
    return 0;
}
