#include <math.h>
#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "TSL2561.h"

int main(void) {
    stdio_init_all();
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, TSL2561Minimal::I2C_ADDRESS);
    /* Light meter with alarm: auto-ranged lux every 2 s for 30 s, then a ±50 % channel-0
       window with persistence 2 reported while the sensor is covered. */
    TSL2561Full sensor(connection);                     // Create TSL2561 Full driver, (connection)
    sensor.init();                                      // Power up, 1× gain, 402 ms, () → bool
    printf("ID 0x%02X\n", sensor.readId());              // Read the ID register, () → int

    /* Light meter: auto-range picks the most sensitive setting that does not saturate. */
    uint16_t ch0 = 0, ch1 = 0;
    for (int i = 0; i < 15; i++) {
        float lx = sensor.autoRange();                  // Auto-range and read lux, () → float lux
        sensor.readRaw(ch0, ch1);                       // Read both channels, (ch0&, ch1&) → bool
        printf("%9.1f lx  gain %2u  %3u ms  ch0 %5u  ch1 %5u\n", (double)lx, (unsigned)sensor.getGain(),
              (unsigned)sensor.getIntegrationTime(), (unsigned)ch0, (unsigned)ch1);  // Current settings, () → uint8_t / uint16_t
        sleep_ms(2000);
    }

    /* Alarm: a window of ±50 % around the current channel-0 count. */
    sensor.readRaw(ch0, ch1);
    uint32_t high = (uint32_t)ch0 * 3 / 2;
    if (high > 65535) high = 65535;
    sensor.setThresholds(ch0 / 2, (uint16_t)high);      // Set channel-0 window, (low, high) → bool
    sensor.configureInterrupt(1, 2);                    // Level interrupt, 2 cycles outside, (mode, persist) → bool
    printf("window %u .. %u - cover the sensor now\n", (unsigned)(ch0 / 2), (unsigned)high);
    for (int i = 0; i < 10; i++) {
        printf("outside window: %d\n", (int)sensor.pollInterrupt());  // Check ch0 against the window, () → bool
        sleep_ms(1000);
    }

    sensor.clearInterrupt();                            // Release the level interrupt, () → bool
    sensor.configureInterrupt(0, 0);
    sensor.powerDown();                                 // Power down, () → bool
    printf("powered: %d\n", (int)sensor.isPowered());    // Power state, () → bool
    return 0;
}
