#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "As7262.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x49);
    AS7262Minimal sensor(connection);                               // Create AS7262 driver, (connection) → void
    AS7262Channels ch = {};
    if (!sensor.begin()) {                                          // Reset and configure defaults, () → bool
        printf("AS7262 not found\n");
    }

    while (true) {
        bool ok = sensor.read(ch);                                  // Read six calibrated channels, (ch) → bool
        printf("V %.1f B %.1f G %.1f Y %.1f O %.1f R %.1f (ok=%lu)\n", (double)(ch.violet), (double)(ch.blue), (double)(ch.green), (double)(ch.yellow), (double)(ch.orange), (double)(ch.red), (unsigned long)(ok));
        printf("%.0f C\n", (double)(sensor.temperature()));
        sleep_ms(2000);
    }
    return 0;
}
