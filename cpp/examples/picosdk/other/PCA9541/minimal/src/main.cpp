#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "PCA9541.h"

int main(void) {
    stdio_init_all();
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, PCA9541Minimal::I2C_ADDRESS);
    PCA9541Minimal selector(connection);                // Create PCA9541 driver, (connection)
    bool before = selector.hasControl();                // Report control, () → bool
    printf("has control %d\n", (int)before);
    bool ok = selector.acquire();                       // Take the downstream bus, (busInit=false, timeoutMs=20) → bool
    printf("acquire %d\n", (int)ok);
    sleep_ms(1000);
    selector.release();                                 // Give the bus to the other master, () → void
    printf("has control %d\n", (int)selector.hasControl());
    return 0;
}
