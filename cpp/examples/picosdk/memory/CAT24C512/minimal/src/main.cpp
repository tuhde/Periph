#include <stdio.h>
#include <stdint.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "CAT24C512.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 400 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x50, nullptr, nullptr, 2);   // Create I2C connection, (i2c, addr, int=null, en=null, reg_bytes=2)
    CAT24C512Minimal ee(connection);                                    // Create CAT24C512 driver, (connection) → void
    uint8_t v = 0;
    uint8_t counter = 0;

    stdio_init_all();
    while (true) {
        ee.write_byte(0x0000, counter);                                 // Write a single byte, (address 0-65535, value 0-255) → bool
        v = ee.read_byte(0x0000);                                       // Read a single byte, (address 0-65535) → uint8_t
        printf("byte 0x%02X\n", (unsigned)v);
        counter++;
        sleep_ms(1000);
    }
    return 0;
}
