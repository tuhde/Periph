#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CTransportPicoSDK.h"
#include "INA226.h"

// I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
i2c_init(i2c0, 100 * 1000);
gpio_set_function(4, GPIO_FUNC_I2C);
gpio_set_function(5, GPIO_FUNC_I2C);
gpio_pull_up(4);
gpio_pull_up(5);
I2CTransportPicoSDK transport(i2c0, 0x40);
INA226Full ina(transport, /*r_shunt=*/0.1f, /*max_current=*/2.0f);

int main(void) {

    stdio_init_all();


    // 64-sample averaging smooths out switching noise from DC/DC converters
    ina.configure(3, 4, 4, 7);

    // latch the alert so a brief spike is not missed between loop iterations
    ina.set_alert(INA226Full::POL, 1.0f, false, true);

    printf("V          A          W\n");
    while (true) {

    // wait for a fresh conversion to avoid reading stale register values
    while (!ina.conversion_ready()) {}

    printf("%.3f", ina.voltage()); printf("V   ");
    printf("%.4f", ina.current()); printf("A   ");
    printf("%.4f", ina.power()); printf("W\n");

    // reading alert_flags clears the latch — do this after printing measurements
    if (ina.alert_flags() & INA226Full::AFF) {
        printf("ALERT: power limit exceeded\n");
    }

    sleep_ms(1000);
        sleep_ms(10);
    }

    return 0;
}
