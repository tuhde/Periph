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
    HTU31DMinimal sensor(connection);                               // Create HTU31D driver, (connection) → void
    sensor.init();                                                  // Soft reset, () → void
    while (true) {
        float t = 0, rh = 0;
        bool ok = sensor.read(t, rh);                               // Read temperature and humidity, (temperature_c&, humidity_pct&) → bool
        printf("temperature %.2f C humidity %.1f %%RH (ok=%lu)\n", (double)(t), (double)(rh), (unsigned long)(ok));
        sleep_ms(2000);
    }
    return 0;
}
