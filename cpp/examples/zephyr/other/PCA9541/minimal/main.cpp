#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "PCA9541.h"

#define I2C_NODE DT_NODELABEL(i2c0)

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, PCA9541Minimal::I2C_ADDRESS);
    PCA9541Minimal selector(connection);                // Create PCA9541 driver, (connection)
    bool before = selector.hasControl();                // Report control, () → bool
    printk("has control %d\n", (int)before);
    bool ok = selector.acquire();                       // Take the downstream bus, (busInit=false, timeoutMs=20) → bool
    printk("acquire %d\n", (int)ok);
    k_sleep(K_MSEC(1000));
    selector.release();                                 // Give the bus to the other master, () → void
    printk("has control %d\n", (int)selector.hasControl());
    return 0;
}
