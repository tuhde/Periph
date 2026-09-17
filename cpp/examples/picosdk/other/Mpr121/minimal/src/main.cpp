#include <stdio.h>
#include "pico/stdlib.h"
#include "hardware/i2c.h"
#include "I2CConnectionPicoSDK.h"
#include "Mpr121.h"

int main(void) {
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);

    I2CConnectionPicoSDK connection(i2c0, 0x5A);                                   // Create I2C connection, (i2c0, addr=0x5A) → I2CConnectionPicoSDK
    MPR121Minimal mpr(connection);                                                 // Create MPR121 Minimal, (connection) → MPR121Minimal

    stdio_init_all();
    while (true) {
        uint16_t t = mpr.touched();                                                // Read 12-bit touch bitmask, () → uint16_t bitmask
        printf("touched=0x%03X\n", t);
        sleep_ms(1000);
    }
}
