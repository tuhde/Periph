#include <stdio.h>
#include "pico/stdlib.h"
#include "hardware/i2c.h"
#include "I2CConnectionPicoSDK.h"
#include "Apds9930.h"

int main(void) {
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);

    I2CConnectionPicoSDK connection(i2c0, 0x39);                                   // Create I2C connection, (i2c0, addr=0x39) → I2CConnectionPicoSDK
    APDS9930Minimal apds(connection);                                              // Create APDS-9930 Minimal, (connection) → APDS9930Minimal
                                                                                   // initialises with ATIME=0xDB, PTIME=0xFF, PPULSE=8, CONTROL=0x20

    stdio_init_all();
    sleep_ms(110);
    while (true) {
        float lx = apds.lux();                                                      // Read ambient illuminance, () → float lx
                                                                                   // IR-compensated lux via Ch0/Ch1 difference
        uint16_t p = apds.proximity();                                              // Read proximity count, () → uint16_t count
                                                                                   // 16-bit ADC value; higher = closer object
        printf("lux=%.1f lx  proximity=%u\n", lx, p);
        sleep_ms(1000);
    }
}