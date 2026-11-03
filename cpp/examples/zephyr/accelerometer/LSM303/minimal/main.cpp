#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <math.h>
#include "I2CConnectionZephyr.h"
#include "LSM303.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define PRINTF(...) printk(__VA_ARGS__)
#define SLEEP_MS(ms) k_sleep(K_MSEC(ms))

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr accel_connection(i2c_dev, LSM303Minimal::I2C_ADDRESS_ACCEL);
    I2CConnectionZephyr mag_connection(i2c_dev, LSM303Minimal::I2C_ADDRESS_MAG);
    LSM303Minimal accel(accel_connection, mag_connection);  // Create LSM303 driver, (accel_connection, mag_connection)


    while (true) {
        float ax, ay, az;
        float mx, my, mz;
        accel.readAcceleration(ax, ay, az);      // Read acceleration, (x, y, z) → g, g, g
        accel.readMagnetic(mx, my, mz);            // Read magnetic field, (x, y, z) → uT, uT, uT
        PRINTF("accel x=%.4f y=%.4f z=%.4f g\n", (double)ax, (double)ay, (double)az);
        PRINTF("mag   x=%.2f y=%.2f z=%.2f uT\n", (double)mx, (double)my, (double)mz);
        SLEEP_MS(1000);
    }
    return 0;
}
