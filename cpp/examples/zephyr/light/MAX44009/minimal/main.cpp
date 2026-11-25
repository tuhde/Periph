#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "MAX44009.h"

#define I2C_NODE DT_NODELABEL(i2c0)

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, MAX44009Minimal::I2C_ADDRESS);
    MAX44009Minimal sensor(connection);                 // Create MAX44009 driver, (connection)
    for (int i = 0; i < 10; i++) {
        float lx = sensor.lux();                        // Read illuminance, () → float lux
        printk("lux %.2f lx\n", (double)lx);
        k_sleep(K_MSEC(1000));
    }
    return 0;
}
