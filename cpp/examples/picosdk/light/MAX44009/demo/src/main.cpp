#include <math.h>
#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "MAX44009.h"

int main(void) {
    stdio_init_all();
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, MAX44009Minimal::I2C_ADDRESS);
    MAX44009Full sensor(connection);                    // Create MAX44009 Full driver, (connection)
    // --- Configuration and first reading ---
    MAX44009Full::Config cfg = sensor.getConfig();      // Read CONFIG, () → Config
    float lx = sensor.lux();                            // Read illuminance, () → float lux
    float res = sensor.resolution();                    // Lux per LSB, () → float lux
    printf("manual %d, lux %.2f, resolution %.3f lx/LSB\n", (int)cfg.manual, (double)lx, (double)res);

    // --- Arm a window of +-30 % around the current illuminance ---
    // The captureless callback only records the status; the loop below reacts to it.
    static volatile uint8_t pending = 0;
    sensor.setThresholdDelay(2.0f);                     // Set the threshold delay, (seconds) → void
    sensor.setThresholds(lx * 0.7f, lx * 1.3f);         // Set the interrupt window, (lowLux, highLux) → bool
    sensor.enableInterrupt();                           // Enable the interrupt, () → void
    sensor.onInterrupt([](uint8_t status) { pending = status; });  // Subscribe, (callback(status), intPin=nullptr) → void

    // --- Run for 60 s: react to interrupts, print the raw reading once a second ---
    for (int s = 0; s < 60; s++) {
        sensor.service();                               // Poll once and dispatch the callback, () → void
        if (pending) {
            pending = 0;
            float now = sensor.lux();                   // Read illuminance, () → float lux
            float level = now < 0.1f ? 0.1f : (now > 10000.0f ? 10000.0f : now);
            float backlight = 10.0f + 90.0f * (log10f(level) + 1.0f) / 5.0f;
            printf("lux %.2f -> backlight %.0f %%\n", (double)now, (double)backlight);
            sensor.setThresholds(now * 0.7f, now * 1.3f);  // Set the interrupt window, (lowLux, highLux) → bool
        }
        uint8_t e = 0, m = 0;
        sensor.readRaw(e, m);                           // Read exponent and mantissa, (exponent&, mantissa&) → bool overrange
        printf("E=%d M=%d overrange=%d\n", (int)e, (int)m, (int)sensor.isOverrange());
        sleep_ms(1000);
    }
    sensor.disableInterrupt();                          // Disable the interrupt, () → void
    sensor.offInterrupt();                              // Unsubscribe, () → void
    return 0;
}
