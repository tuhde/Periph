#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "TSL2561.h"

#define I2C_NODE DT_NODELABEL(i2c0)

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, TSL2561Minimal::I2C_ADDRESS);
    TSL2561Minimal sensor(connection);                  // Create TSL2561 driver, (connection)
    sensor.init();                                      // Power up, 1× gain, 402 ms, wait one integration, () → bool
    for (int i = 0; i < 10; i++) {
        float lx = sensor.readLux();                    // Read illuminance, () → float lux
        printk("lux %.1f lx\n", (double)lx);
        k_sleep(K_MSEC(1000));
    }
    return 0;
}
