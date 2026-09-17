#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "L3G4200D.h"

#ifndef L3G4200D_I2C_NODE
#define L3G4200D_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef L3G4200D_ADDR
#define L3G4200D_ADDR 0x68
#endif

int main(void) {
    const struct device *dev = DEVICE_DT_GET(L3G4200D_I2C_NODE);
    I2CConnectionZephyr connection(dev, L3G4200D_ADDR);
    L3G4200DMinimal gyro(connection);                      // Create L3G4200D driver, (connection, spi=false)

    for (int i = 0; i < 10; i++) {
        float x, y, z;
        gyro.angular_rate(x, y, z);                        // Read X/Y/Z angular rate, () → (float, float, float) rad/s
        printk("X=%.2f Y=%.2f Z=%.2f rad/s\n", x, y, z);
        k_sleep(K_MSEC(100));
    }
    printk("===DONE: 0 passed, 0 failed===\n");
    return 0;
}
