#include <stdio.h>
#include <stdint.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "AT24C02N.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 400 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x50);   // Create I2C connection, (i2c, addr)
    I2CConnectionPicoSDK idConnection(i2c0, 0x7C);                      // reserved Device ID address
    AT24C02NFull ee(connection);                                       // Create AT24C02N driver, (connection) → void
    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate
    uint8_t data[40], back[40];
    uint8_t page[16];
    for (uint8_t i = 0; i < 40; i++) data[i] = i;
    for (uint8_t i = 0; i < 16; i++) page[i] = i;

    printf("size %lu bytes, page %u bytes\n", (unsigned long)ee.size(), (unsigned)ee.page_size());
    // ee.size() → uint32_t (256), ee.page_size() → uint16_t (8)
    ee.set_max_chunk(16);                                               // Set max bytes per bus transaction, (value 1-32, default 30) → void
    printf("max_chunk %u\n", (unsigned)ee.max_chunk());
    // ee.max_chunk() → uint8_t

    ee.write_byte(0x0010, 0xA5);                                        // Write a single byte, (address 0-255, value 0-255) → bool
                                                                        // ACK-polled until the write cycle ends
    printf("byte 0x%02X\n", (unsigned)ee.read_byte(0x0010));
    // ee.read_byte(address) → uint8_t, random read
    ee.write(0x0040, data, 40);                                         // Write data, (address, data, length) → bool
                                                                        // split at 8-byte page boundaries and max_chunk, ACK-polled per chunk
    ee.read(0x0040, back, 40);                                          // Sequential read, (address, buf, length) → bool
                                                                        // split into max_chunk-byte transactions
    printf("block[39] = %u\n", (unsigned)back[39]);
    ee.write_page(0x00B8, page, 8);                                    // Write within one page, (address, data, length) → bool
                                                                        // false if the data crosses a page boundary
    ee.fill(0x0040, 40, 0xFF);                                          // Fill a range, (address, length, value=0) → bool
                                                                        // page-wise writes of value
    printf("ready %u\n", (unsigned)ee.wait_ready(10));
    // ee.wait_ready(timeout_ms) → bool, ACK-polls the chip
    printf("done\n");
    while (true) sleep_ms(1000);
    return 0;
}
