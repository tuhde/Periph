#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "HS30xx.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x44);
    HS30xxMinimal sensor(connection);                               // Create HS30xx driver, (connection) → void
    stdio_init_all();
    float t = 0, rh = 0;

    while (true) {
        bool ok = sensor.read(t, rh);                               // Read temperature and humidity, (t, rh) → bool °C, %RH
        printf("%.2f C, %.1f %%RH (ok=%lu)\n", t, rh, (unsigned long)(ok));
        sleep_ms(2000);
    }
    return 0;
}
