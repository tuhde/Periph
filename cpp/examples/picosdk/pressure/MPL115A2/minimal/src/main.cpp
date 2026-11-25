#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "MPL115A2.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x60);
    MPL115A2Minimal baro(connection);                   // Create MPL115A2 driver, (connection)

    static int passed = 0, failed = 0;

    stdio_init_all();
    sleep_ms(2000);

    for (int i = 0; i < 5; i++) {
        float p = baro.read_pressure();                   // Read pressure, () → float Pa
        float t = baro.read_temperature();                // Read temperature, () → float °C (approximate)
        printf("%.0f Pa, %.1f C\n", p, t);
        sleep_ms(1000);
    }

    printf("===DONE: ");
    printf("%d", passed);
    printf(" passed, ");
    printf("%d", failed);
    printf(" failed===\n");
    while (true) {
    sleep_ms(1000); 
        sleep_ms(10);
    }

    return 0;
}
