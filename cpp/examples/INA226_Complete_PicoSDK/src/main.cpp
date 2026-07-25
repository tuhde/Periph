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


    printf("0x%X\n", (unsigned)ina.manufacturer_id());
    printf("0x%X\n", (unsigned)ina.die_id());

    printf("%d\n", ina.voltage());
    printf("%d\n", ina.shunt_voltage());
    printf("%d\n", ina.current());
    printf("%d\n", ina.power());
    printf("%d\n", ina.conversion_ready());
    printf("%d\n", ina.overflow());

    ina.configure(3, 4, 4, 7);

    ina.set_alert(INA226Full::POL, 1.0f, false, true);
    printf("0x%X\n", (unsigned)ina.alert_flags());

    ina.set_alert(INA226Full::BOL, 5.5f);
    ina.set_alert(INA226Full::BUL, 4.5f);
    ina.set_alert(INA226Full::SOL, 0.05f);
    ina.set_alert(INA226Full::CNVR);

    ina.shutdown();
    sleep_ms(1);
    ina.wake();

    ina.reset();
    return 0;
}
