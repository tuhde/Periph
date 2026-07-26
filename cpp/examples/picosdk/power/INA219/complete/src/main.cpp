#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CTransportPicoSDK.h"
#include "INA219.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CTransportPicoSDK transport(i2c0, 0x40);
    INA219Full ina(transport, /*r_shunt=*/0.1f, /*max_current=*/2.0f);

    stdio_init_all();

    printf("%d\n", ina.voltage());
    printf("%d\n", ina.shunt_voltage());
    printf("%d\n", ina.current());
    printf("%d\n", ina.power());
    printf("%d\n", ina.conversion_ready());
    printf("%d\n", ina.overflow());

    ina.configure(1, 3, 0x03, 0x03, 7);

    ina.shutdown();
    sleep_ms(1);
    ina.wake();

    ina.reset();
    return 0;
}
