#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "Bh1750.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x23);
    BH1750Minimal sensor(connection);                               // Create BH1750 driver, (connection) → void
    sensor.init();                                                  // Power on, () → void
    while (true) {
        float lux = 0;
        bool ok = sensor.read(lux);                                 // Measure illuminance, (lux&) → bool
        printf("illuminance %.1f lx (ok=%lu)\n", (double)(lux), (unsigned long)(ok));
        sleep_ms(1000);
    }
    return 0;
}
