#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "Ms5611.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x77);
    MS5611Minimal sensor(connection);                               // Create MS5611 driver, (connection) → void
    sensor.init();                                                  // Reset, read PROM, verify CRC-4, () → bool
    while (true) {
        float t = 0, p = 0;
        bool ok = sensor.read(t, p);                                // Read temperature and pressure, (temperature_c&, pressure_pa&) → bool
        printf("temperature %.2f C pressure %.1f Pa (ok=%lu)\n", (double)(t), (double)(p), (unsigned long)(ok));
        sleep_ms(1000);
    }
    return 0;
}
