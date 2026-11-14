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
    AT24C64Minimal ee(connection);                                    // Create AT24C64 driver, (connection) → void
    uint8_t v = 0;
    uint8_t counter = 0;

    while (true) {
        ee.write_byte(0x0000, counter);                                 // Write a single byte, (address 0-8191, value 0-255) → bool
        v = ee.read_byte(0x0000);                                       // Read a single byte, (address 0-8191) → uint8_t
        printk("byte 0x%02X\n", (unsigned)v);
        counter++;
        k_sleep(K_MSEC(1000));
    }
    return 0;
}
