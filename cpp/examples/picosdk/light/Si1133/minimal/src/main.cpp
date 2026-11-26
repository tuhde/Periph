#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "Si1133.h"

int main(void) {
    stdio_init_all();
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x55);
    Si1133Minimal sensor(connection);                              // Create Si1133 driver, (connection) → void
    sensor.init();                                                 // Check PART_ID, reset, UV + white channels, () → bool
    while (true) {
        int32_t uv = 0, white = 0;
        bool ok = sensor.read(uv, white);                          // Forced measurement, (uv&, white&) → bool
        printf("UV %ld white %ld (ok=%u)\n", (long)(uv), (long)(white), (unsigned)(ok));
        sleep_ms(1000);
    }
    return 0;
}
