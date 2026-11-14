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
    I2CConnectionPicoSDK idConnection(i2c0, 0x7C);                      // reserved Device ID address
    CAT24C512Full ee(connection);                                       // Create CAT24C512 driver, (connection) → void
    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate
    uint8_t cb[4];
    uint8_t pattern[384], back[384];
    for (uint16_t i = 0; i < 384; i++) pattern[i] = (uint8_t)((i * 7) & 0xFF);

    // --- Persistent boot counter: 4-byte big-endian value at address 0 ---
    ee.read(0x0000, cb, 4);                                             // Sequential read, (address, buf, length) → bool
    uint32_t counter = ((uint32_t)cb[0] << 24) | ((uint32_t)cb[1] << 16) | ((uint32_t)cb[2] << 8) | cb[3];
    counter++;
    cb[0] = counter >> 24; cb[1] = counter >> 16; cb[2] = counter >> 8; cb[3] = counter;
    ee.write(0x0000, cb, 4);                                            // Write data, (address, data, length) → bool
    printf("Boot counter: %lu\n", (unsigned long)counter);

    // --- Page-write benchmark: 3 pages, 128-byte aligned ---
    uint32_t t0 = to_ms_since_boot(get_absolute_time());
    ee.write(0x0080, pattern, 384);                                     // Write data, (address, data, length) → bool
    uint32_t elapsed = to_ms_since_boot(get_absolute_time()) - t0;
    printf("Wrote 384 bytes in %lu ms (incl. ACK-polled write cycles)\n", (unsigned long)elapsed);
    ee.read(0x0080, back, 384);                                         // Sequential read, (address, buf, length) → bool
    bool same = true;
    for (uint16_t i = 0; i < 384; i++) same = same && back[i] == pattern[i];
    printf("Read-back matches: %u\n", (unsigned)same);

    // --- A write that crosses a page boundary is refused ---
    printf("write_page across boundary accepted: %u\n", (unsigned)ee.write_page(0x00FC, pattern, 8));
    // ee.write_page(address, data, length) → bool, false across a page boundary
    ee.fill(0x0080, 384);                                               // Fill a range, (address, length, value=0) → bool
    printf("size %lu, page %u, ready %u\n", (unsigned long)ee.size(), (unsigned)ee.page_size(), (unsigned)ee.wait_ready(10));
    while (true) sleep_ms(1000);
    return 0;
}
