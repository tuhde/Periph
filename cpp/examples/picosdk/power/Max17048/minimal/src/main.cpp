#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "Max17048.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate
    I2CConnectionPicoSDK connection(i2c0, 0x36);
    Max17048Minimal gauge(connection, 1);                   // Create MAX17048 driver, (connection, cells)

    while (true) {
        printf("%.3f V  %.1f %%\n", (double)(gauge.voltage()), (double)(gauge.percent()));          // Read V / %, () → float each
        sleep_ms(1000);
    }
    return 0;
}
