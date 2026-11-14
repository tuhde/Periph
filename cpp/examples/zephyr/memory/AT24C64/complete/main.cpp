#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <stdint.h>
#include "I2CConnectionZephyr.h"
#include "AT24C64.h"

#define I2C_NODE   DT_NODELABEL(i2c0)
#define FRAM_ADDR  0x50

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, FRAM_ADDR, nullptr, nullptr, 2);  // Create I2C connection, (dev, addr, int=null, en=null, reg_bytes=2)
    I2CConnectionZephyr idConnection(i2c_dev, 0x7C);                          // reserved Device ID address
    AT24C64Full ee(connection);                                       // Create AT24C64 driver, (connection) → void
    uint8_t data[40], back[40];
    uint8_t page[16];
    for (uint8_t i = 0; i < 40; i++) data[i] = i;
    for (uint8_t i = 0; i < 16; i++) page[i] = i;

    printk("size %lu bytes, page %u bytes\n", (unsigned long)ee.size(), (unsigned)ee.page_size());
    // ee.size() → uint32_t (8192), ee.page_size() → uint16_t (32)
    ee.set_max_chunk(16);                                               // Set max bytes per bus transaction, (value 1-32, default 30) → void
    printk("max_chunk %u\n", (unsigned)ee.max_chunk());
    // ee.max_chunk() → uint8_t

    ee.write_byte(0x0010, 0xA5);                                        // Write a single byte, (address 0-8191, value 0-255) → bool
                                                                        // ACK-polled until the write cycle ends
    printk("byte 0x%02X\n", (unsigned)ee.read_byte(0x0010));
    // ee.read_byte(address) → uint8_t, random read
    ee.write(0x0100, data, 40);                                         // Write data, (address, data, length) → bool
                                                                        // split at 32-byte page boundaries and max_chunk, ACK-polled per chunk
    ee.read(0x0100, back, 40);                                          // Sequential read, (address, buf, length) → bool
                                                                        // split into max_chunk-byte transactions
    printk("block[39] = %u\n", (unsigned)back[39]);
    ee.write_page(0x0180, page, 16);                                    // Write within one page, (address, data, length) → bool
                                                                        // false if the data crosses a page boundary
    ee.fill(0x0100, 40, 0xFF);                                          // Fill a range, (address, length, value=0) → bool
                                                                        // page-wise writes of value
    printk("ready %u\n", (unsigned)ee.wait_ready(10));
    // ee.wait_ready(timeout_ms) → bool, ACK-polls the chip
    printk("done\n");
    while (true) k_sleep(K_MSEC(1000));
    return 0;
}
