#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "Isl29125.h"

#define I2C_NODE DT_NODELABEL(i2c0)

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, Isl29125Minimal::I2C_ADDRESS);
    Isl29125Minimal sensor(connection);                             // Create ISL29125 driver, (connection)
    for (int i = 0; i < 10; i++) {
        Isl29125Counts c = {0, 0, 0};
        sensor.read(c);                                             // Read the three channels, (counts&) → bool
        printk("R %lu G %lu B %lu\n", (unsigned long)(c.red), (unsigned long)(c.green), (unsigned long)(c.blue));
        k_sleep(K_MSEC(1000));
    }
    return 0;
}
