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
    24AA02UIDFull eeprom(connection);

    stdio_init_all();

    uint8_t uid[4];
    eeprom.read_uid(uid);                                       // Read 32-bit unique serial number, (buf[4]) → void
                                                                // reads 4 bytes at 0xFC-0xFF
    printf("UID bytes: ");
    for (uint8_t i = 0; i < 4; i++) {
        if (uid[i] < 0x10) printf("%d", '0');
        printf("0x%X", (unsigned)uid[i]);
    }
    printf("\n");

    uint32_t uid_int = ((uint32_t)uid[0] << 24) | ((uint32_t)uid[1] << 16)
                     | ((uint32_t)uid[2] << 8)  |  (uint32_t)uid[3];
    printf("UID int:   ");
    printf("%d\n", uid_int);

    uint8_t mfr = eeprom.read_manufacturer_code();             // Read manufacturer code, () → byte
                                                                // reads 0xFA; expect 0x29 (Microchip)
    uint8_t dev = eeprom.read_device_code();                   // Read device code, () → byte
                                                                // reads 0xFB; expect 0x41
    printf("MFR: 0x");
    if (mfr < 0x10) printf("%d", '0');
    printf("0x%X", (unsigned)mfr);
    printf("  DEV: 0x");
    if (dev < 0x10) printf("%d", '0');
    printf("0x%X\n", (unsigned)dev);

    uint8_t first = eeprom.read_byte(0x00);                    // Read a single byte, (address=0x00-0x7F) → byte
                                                                // random read at user EEPROM address
    printf("First byte: 0x");
    if (first < 0x10) printf("%d", '0');
    printf("0x%X\n", (unsigned)first);

    eeprom.write_byte(0x10, 0xA5);                             // Write a single byte, (address, value) → void
                                                                // byte write + delay until complete (max 5 ms)
    uint8_t verify = eeprom.read_byte(0x10);                   // Read a single byte, (address=0x00-0x7F) → byte
    printf("Wrote 0xA5, read back: 0x");
    if (verify < 0x10) printf("%d", '0');
    printf("0x%X\n", (unsigned)verify);

    uint8_t buf[8];
    eeprom.read(0x20, buf, 8);                                 // Sequential read, (address, buf, length) → void
                                                                // reads 8 bytes starting at address
    printf("Block @ 0x20: ");
    for (uint8_t i = 0; i < 8; i++) {
        if (buf[i] < 0x10) printf("%d", '0');
        printf("0x%X", (unsigned)buf[i]);
        printf("%d", ' ');
    }
    printf("\n");

    uint8_t page_data[] = { 0x01, 0x02, 0x03, 0x04 };
    eeprom.write_page(0x40, page_data, 4);                     // Page write, (address, data, length) → void
                                                                // writes up to 8 bytes within one page

    uint8_t cross[] = { 0xAA, 0xBB, 0xCC, 0xDD, 0xEE };
    eeprom.write(0x44, cross, 5);                              // Arbitrary-length write, (address, data, length) → void
                                                                // splits at 8-byte page boundaries; waits for each chunk
    printf("Multi-page write complete\n");
    return 0;
}
