#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "LSM303.h"

#define I2C_NODE DT_NODELABEL(i2c0)

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr accel_connection(i2c_dev, 0x19);
    I2CConnectionZephyr mag_connection(i2c_dev, 0x1E);
    LSM303Minimal sensor(accel_connection, mag_connection);  // Create LSM303 driver, (accel, mag connections)

    while (1) {
        float ax, ay, az;
        sensor.read_acceleration(ax, ay, az);                // Read 3-axis acceleration, (x, y, z) → g, g, g
        float mx, my, mz;
        sensor.read_magnetic(mx, my, mz);                    // Read 3-axis magnetic field, (x, y, z) → µT, µT, µT
        printk("a=(%.3f %.3f %.3f) g  m=(%.1f %.1f %.1f) uT\n",
               (double)ax, (double)ay, (double)az, (double)mx, (double)my, (double)mz);
        k_sleep(K_MSEC(100));
    }
    return 0;
}
