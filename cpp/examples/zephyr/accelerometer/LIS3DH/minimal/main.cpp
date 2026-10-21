#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <math.h>
#include "I2CConnectionZephyr.h"
#include "LIS3DH.h"

#ifndef LIS3DH_I2C_NODE
#define LIS3DH_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef LIS3DH_ADDR
#define LIS3DH_ADDR 0x18
#endif

static void lis3dh_sleep_ms(uint32_t ms) { k_sleep(K_MSEC(ms)); }

int main(void) {
    const struct device *dev = DEVICE_DT_GET(LIS3DH_I2C_NODE);
    I2CConnectionZephyr connection(dev, LIS3DH_ADDR);
    LIS3DHMinimal accel(connection);                            // Create LIS3DH driver, (connection)
    for (int i = 0; i < 10; i++) {
        float x, y, z;
        accel.read(x, y, z);                                // Read 3-axis acceleration, (x, y, z) → g, g, g
        printf("x=%.3f y=%.3f z=%.3f g\n", x, y, z);
        lis3dh_sleep_ms(100);
    }
    return 0;
}
