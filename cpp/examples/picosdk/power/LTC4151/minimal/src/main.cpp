#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "LTC4151.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x6A);
    LTC4151Minimal ltc(connection, 0.02f);                              // Create LTC4151 driver, (connection, r_sense=0.02 Ω) → void
    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate
    while (true) {
        printf("V=%.3f V\n", ltc.voltage());                            // Read supply voltage, () → float V
        printf("I=%.4f A\n", ltc.current());                            // Read load current, () → float A
        printf("P=%.3f W\n", ltc.power());                              // Read load power, () → float W
        sleep_ms(1000);
    }
    return 0;
}
