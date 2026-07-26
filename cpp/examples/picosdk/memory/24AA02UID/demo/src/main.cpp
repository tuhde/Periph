#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CTransportPicoSDK.h"
#include "24AA02UID.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CTransportPicoSDK transport(i2c0, 0x50);
    24AA02UIDFull eeprom(transport);

    stdio_init_all();

    // --- Read the chip's factory-programmed 32-bit serial number ---
    // The UID at 0xFC-0xFF never changes and identifies the device
    // across the entire 256-byte address space.
    uint8_t uid[4];
    eeprom.read_uid(uid);                                       // Read 32-bit unique serial number, (buf[4]) → void
                                                                // reads 4 bytes at 0xFC-0xFF
    printf("Device UID: 0x");
    for (uint8_t i = 0; i < 4; i++) {
        if (uid[i] < 0x10) printf("%d", '0');
        printf("0x%X", (unsigned)uid[i]);
    }
    printf("\n");
    uint32_t uid_int = ((uint32_t)uid[0] << 24) | ((uint32_t)uid[1] << 16)
                     | ((uint32_t)uid[2] << 8)  |  (uint32_t)uid[3];
    printf("Device UID int: ");
    printf("%d\n", uid_int);

    // --- Maintain a 4-byte boot counter in user EEPROM at 0x00-0x03 ---
    // Read the existing value (or zero on a fresh chip), increment,
    // write back as 4 big-endian bytes. The user EEPROM is rewritable;
    // the UID region above 0x80 is not, so the two stay independent.
    uint8_t existing[4];
    eeprom.read(0x00, existing, 4);                             // Sequential read, (address, buf, length) → void
                                                                // reads 4 bytes from user EEPROM
    uint32_t counter = ((uint32_t)existing[0] << 24)
                     | ((uint32_t)existing[1] << 16)
                     | ((uint32_t)existing[2] << 8)
                     |  (uint32_t)existing[3];
    counter++;
    uint8_t updated[4] = {
        (uint8_t)(counter >> 24), (uint8_t)(counter >> 16),
        (uint8_t)(counter >> 8),  (uint8_t)(counter)
    };
    eeprom.write(0x00, updated, 4);                             // Arbitrary-length write, (address, data, length) → void
                                                                // writes 4 bytes
    printf("Boot count: ");
    printf("%d\n", counter);
    while (true) {

    // --- Loop reading the UID only, showing it never changes ---
    // The two distinct areas of the chip (immutable identification
    // above 0x80, rewritable storage below 0x80) are exercised
    // independently.
    uint8_t uid[4];
    eeprom.read_uid(uid);                                       // Read 32-bit unique serial number, (buf[4]) → void
    printf("UID: 0x");
    for (uint8_t i = 0; i < 4; i++) {
        if (uid[i] < 0x10) printf("%d", '0');
        printf("0x%X", (unsigned)uid[i]);
    }
    printf("  (counter is in user EEPROM 0x00-0x03)\n");
    sleep_ms(2000);
        sleep_ms(10);
    }

    return 0;
}
