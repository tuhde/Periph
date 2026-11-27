#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "Si1145.h"

static unsigned uv_whole(float uv) { return (unsigned)(uv * 100.0f + 0.5f) / 100; }
static unsigned uv_frac(float uv) { return (unsigned)(uv * 100.0f + 0.5f) % 100; }

int main(void) {
    stdio_init_all();
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x60);
    Si1145Minimal sensor(connection);                              // Create Si1145 driver, (connection) → void
    sensor.init();                                                 // Check PART_ID, HW_KEY, UV + ALS + PS1, () → bool
    while (true) {
        Si1145Reading r;
        bool ok = sensor.read(r);                                  // Forced PS + ALS, (reading&) → bool
        printf("prox %u UV %u.%02u vis %u ir %u (ok=%u)\n", (unsigned)r.proximity, uv_whole(r.uv_index),
              uv_frac(r.uv_index), (unsigned)r.visible, (unsigned)r.infrared, (unsigned)ok);
        sleep_ms(1000);
    }
    return 0;
}
