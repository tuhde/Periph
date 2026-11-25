#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <stdint.h>
#include "I2CConnectionZephyr.h"
#include "AT24C02N.h"

#define I2C_NODE   DT_NODELABEL(i2c0)
#define FRAM_ADDR  0x50

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, FRAM_ADDR);  // Create I2C connection, (dev, addr)
    I2CConnectionZephyr idConnection(i2c_dev, 0x7C);                          // reserved Device ID address
    AT24C02NFull ee(connection);                                       // Create AT24C02N driver, (connection) → void
    uint8_t cb[4];
    uint8_t pattern[24], back[24];
    for (uint16_t i = 0; i < 24; i++) pattern[i] = (uint8_t)((i * 7) & 0xFF);

    // --- Persistent boot counter: 4-byte big-endian value at address 0 ---
    ee.read(0x0000, cb, 4);                                             // Sequential read, (address, buf, length) → bool
    uint32_t counter = ((uint32_t)cb[0] << 24) | ((uint32_t)cb[1] << 16) | ((uint32_t)cb[2] << 8) | cb[3];
    counter++;
    cb[0] = counter >> 24; cb[1] = counter >> 16; cb[2] = counter >> 8; cb[3] = counter;
    ee.write(0x0000, cb, 4);                                            // Write data, (address, data, length) → bool
    printk("Boot counter: %lu\n", (unsigned long)counter);

    // --- Page-write benchmark: 3 pages, 8-byte aligned ---
    uint32_t t0 = k_uptime_get_32();
    ee.write(0x0080, pattern, 24);                                     // Write data, (address, data, length) → bool
    uint32_t elapsed = k_uptime_get_32() - t0;
    printk("Wrote 24 bytes in %lu ms (incl. ACK-polled write cycles)\n", (unsigned long)elapsed);
    ee.read(0x0080, back, 24);                                         // Sequential read, (address, buf, length) → bool
    bool same = true;
    for (uint16_t i = 0; i < 24; i++) same = same && back[i] == pattern[i];
    printk("Read-back matches: %u\n", (unsigned)same);

    // --- A write that crosses a page boundary is refused ---
    printk("write_page across boundary accepted: %u\n", (unsigned)ee.write_page(0x00F4, pattern, 8));
    // ee.write_page(address, data, length) → bool, false across a page boundary
    ee.fill(0x0080, 24);                                               // Fill a range, (address, length, value=0) → bool
    printk("size %lu, page %u, ready %u\n", (unsigned long)ee.size(), (unsigned)ee.page_size(), (unsigned)ee.wait_ready(10));
    while (true) k_sleep(K_MSEC(1000));
    return 0;
}
