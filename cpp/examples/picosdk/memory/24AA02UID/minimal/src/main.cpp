#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "24AA02UID.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x50);
    24AA02UIDMinimal eeprom(connection);

    stdio_init_all();
    while (true) {

    uint8_t uid[4];
    eeprom.read_uid(uid);                                       // Read 32-bit unique serial number, (buf[4]) → void
    printf("UID: ");
    for (uint8_t i = 0; i < 4; i++) {
        if (uid[i] < 0x10) printf("%d", '0');
        printf("0x%X", (unsigned)uid[i]);
    }
    printf("\n");
    sleep_ms(2000);
        sleep_ms(10);
    }

    return 0;
}
