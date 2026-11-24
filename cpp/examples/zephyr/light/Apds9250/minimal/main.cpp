#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "Apds9250.h"

#define I2C_NODE DT_NODELABEL(i2c0)

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, Apds9250Minimal::I2C_ADDRESS);
    Apds9250Minimal sensor(connection);                             // Create APDS-9250 driver, (connection)
    for (int i = 0; i < 10; i++) {
        Apds9250Counts c = {0, 0, 0, 0};
        sensor.read(c);                                             // Read the four channels, (counts&) → bool
        printk("R %lu G %lu B %lu IR %lu\n", (unsigned long)(c.red), (unsigned long)(c.green), (unsigned long)(c.blue), (unsigned long)(c.ir));
        k_sleep(K_MSEC(1000));
    }
    return 0;
}
