#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CTransportPicoSDK.h"
#include "INA219.h"

// I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
i2c_init(i2c0, 100 * 1000);
gpio_set_function(4, GPIO_FUNC_I2C);
gpio_set_function(5, GPIO_FUNC_I2C);
gpio_pull_up(4);
gpio_pull_up(5);
I2CTransportPicoSDK transport(i2c0, 0x40);
INA219Full ina(transport, /*r_shunt=*/0.1f, /*max_current=*/2.0f);

int main(void) {

    stdio_init_all();


    ina.configure(1, 3, 0x0F, 0x0F, 7);

    printf("V          A          W\n");
    while (true) {

    while (!ina.conversion_ready()) {}

    printf("%.3f", ina.voltage()); printf("V   ");
    printf("%.4f", ina.current()); printf("A   ");
    printf("%.4f", ina.power()); printf("W\n");

    sleep_ms(1000);
        sleep_ms(10);
    }

    return 0;
}
