#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <stdint.h>
#include "I2CConnectionZephyr.h"
#include "MB85RC.h"

#define I2C_NODE   DT_NODELABEL(i2c0)
#define FRAM_ADDR  0x50

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, FRAM_ADDR, nullptr, nullptr, 2);  // Create I2C connection, (dev, addr, int=null, en=null, reg_bytes=2)
    I2CConnectionZephyr idConnection(i2c_dev, 0x7C);                          // reserved Device ID address
    MB85RCFull fram(connection);                                               // Create MB85RC driver, (connection) → void
    printk("Size: %u bytes\n", (unsigned)fram.size());                       // Get array size, () → uint16_t
                                                                            // always 32768 for the MB85RC256V
    fram.set_max_chunk(16);                                                 // Set max bytes per bus transaction, (max_chunk 1–32=30) → void
    printk("max_chunk: %u\n", (unsigned)fram.max_chunk());                   // Get max bytes per bus transaction, () → uint8_t

    fram.write_byte(0x0010, 0xA5);                                          // Write a single byte, (address 0–32767, value 0–255) → bool
                                                                            // no write cycle, no polling
    uint8_t b = fram.read_byte(0x0010);                                     // Read a single byte, (address 0–32767) → uint8_t
                                                                            // random read
    printk("Byte: 0x%02X\n", (unsigned)b);

    uint8_t block[40];
    for (uint8_t i = 0; i < 40; i++) block[i] = i;
    fram.write(0x0100, block, 40);                                          // Sequential write, (address, data, length) → bool
                                                                            // split into max_chunk-byte transactions
    uint8_t back[40];
    fram.read(0x0100, back, 40);                                            // Sequential read, (address, buf, length) → bool
                                                                            // split into max_chunk-byte transactions
    printk("Block[39]: %u\n", (unsigned)back[39]);

    fram.fill(0x0100, 40, 0xFF);                                            // Fill a range, (address, length, value=0) → bool
                                                                            // writes value over the range in chunks
    fram.read(0x0100, back, 8);                                             // Sequential read, (address, buf, length) → bool
    printk("After fill: 0x%02X\n", (unsigned)back[0]);

    uint16_t manufacturer = 0;
    uint8_t density = 0, product = 0;
    fram.read_device_id(idConnection, &manufacturer, &density, &product, 0x50);// Read Device ID, (id_connection, &mfr, &density, &product, address=0x50) → void
                                                                            // reads 3 bytes via reserved slave address 0x7C
    printk("Device ID: mfr=0x%03X density=0x%X product=0x%02X\n", (unsigned)manufacturer, (unsigned)density, (unsigned)product);
    return 0;
}
