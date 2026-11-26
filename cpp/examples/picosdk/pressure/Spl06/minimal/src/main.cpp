#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "Spl06.h"

int main(void) {
    stdio_init_all();
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x77);
    SPL06Minimal sensor(connection);                               // Create SPL06 driver, (connection) → void
    sensor.init();                                                 // Check ID, read coefficients, apply defaults, () → bool
    while (true) {
        float t = 0, p = 0;
        bool ok = sensor.read(t, p);                               // Measure temperature and pressure, (temperature_c&, pressure_pa&) → bool
        printf("pressure %.2f Pa temperature %.2f C (ok=%lu)\n", (double)(p), (double)(t), (unsigned long)(ok));
        sleep_ms(1000);
    }
    return 0;
}
