#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <math.h>
#include "I2CConnectionZephyr.h"
#include "FXOS8700.h"

#ifndef FXOS8700_I2C_NODE
#define FXOS8700_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef FXOS8700_ADDR
#define FXOS8700_ADDR 0x1E
#endif

static void sleepMs(uint32_t ms) { k_sleep(K_MSEC(ms)); }
static void printVec(const char* label, float x, float y, float z, const char* unit) {
    printk("%s x=%.3f y=%.3f z=%.3f %s\n", label, x, y, z, unit);
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(FXOS8700_I2C_NODE);
    I2CConnectionZephyr connection(dev, FXOS8700_ADDR);
    FXOS8700Minimal compass(connection);                    // Create FXOS8700 driver, (connection)
    for (int i = 0; i < 10; i++) {
        float ax, ay, az, mx, my, mz;
        compass.readAccel(ax, ay, az);                      // Read acceleration, (x, y, z) → g, g, g
        compass.readMag(mx, my, mz);                        // Read magnetic field, (x, y, z) → µT, µT, µT
        printVec("accel", ax, ay, az, "g");
        printVec("mag", mx, my, mz, "uT");
        sleepMs(500);
    }
    return 0;
}
