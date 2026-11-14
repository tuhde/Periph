#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "AM2315.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x5C);
    AM2315Minimal sensor(connection);                                        // Create AM2315 driver, (connection) → void
    stdio_init_all();
    float t = 0, rh = 0;

    while (true) {
        bool ok = sensor.read(t, rh);                                            // Read temperature and humidity, (t, rh) → bool °C, %RH
                                                                                 // previous measurement; reads are kept >= 2 s apart
        printf("%.1f C, %.1f %%RH (ok=%u)\n", t, rh, (unsigned)(ok));
        sleep_ms(2000);
    }
    return 0;
}
