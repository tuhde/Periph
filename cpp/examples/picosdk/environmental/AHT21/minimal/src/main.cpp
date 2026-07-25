#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CTransportPicoSDK.h"
#include "AHT21.h"

// I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
i2c_init(i2c0, 100 * 1000);
gpio_set_function(4, GPIO_FUNC_I2C);
gpio_set_function(5, GPIO_FUNC_I2C);
gpio_pull_up(4);
gpio_pull_up(5);
I2CTransportPicoSDK transport(i2c0, 0x38);
AHT21Minimal aht(transport);

int main(void) {

    stdio_init_all();
    while (true) {

    float t, h;
    aht.read(t, h);                                                    // Trigger measurement, (temperature_c, humidity_pct) → void
    printf("%f", t);   printf(" C  ");
    printf("%f", h);   printf(" %RH\n");
    sleep_ms(1000);
        sleep_ms(10);
    }

    return 0;
}
