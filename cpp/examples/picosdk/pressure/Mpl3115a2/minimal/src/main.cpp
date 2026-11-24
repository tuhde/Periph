#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "Mpl3115a2.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x60);
    MPL3115A2Minimal sensor(connection);                           // Create MPL3115A2 driver, (connection) → void
    sensor.init();                                                 // Reset and apply defaults, () → bool
    while (true) {
        float p = 0, t = 0;
        bool ok = sensor.read(p, t);                               // Read pressure and temperature, (pressure_pa&, temperature_c&) → bool
        printf("pressure %.2f Pa temperature %.2f C (ok=%lu)\n", (double)(p), (double)(t), (unsigned long)(ok));
        sleep_ms(2000);
    }
    return 0;
}
