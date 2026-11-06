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
    MB85RCMinimal fram(connection);                                               // Create MB85RC driver, (connection) → void
    uint8_t counter = 0;
    while (1) {
        fram.write_byte(0x0000, counter);                                   // Write a single byte, (address 0–32767, value 0–255) → bool
        uint8_t value = fram.read_byte(0x0000);                             // Read a single byte, (address 0–32767) → uint8_t
        printk("Stored: %u\n", (unsigned)value);
        counter++;
        k_msleep(1000);
    }
    return 0;
}
