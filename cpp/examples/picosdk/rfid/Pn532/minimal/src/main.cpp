#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "Pn532.h"

static void print_hex(const uint8_t* data, size_t len) {
    for (size_t i = 0; i < len; i++) printf("%02X", data[i]);
}

int main(void) {
    stdio_init_all();
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x24);
    PN532Minimal reader(connection);                               // Create PN532 driver, (connection) → void
    reader.init();                                                 // Wake, check firmware, SAM normal mode, finite retries, () → bool
    while (true) {
        uint8_t uid[10];
        uint8_t len = reader.read_uid(uid);                        // Detect a type A card, (uid*, timeoutMs=1000) → uint8_t length
        printf("UID ");
        if (len) print_hex(uid, len); else printf("none");
        printf("\n");
        sleep_ms(1000);
    }
    return 0;
}
