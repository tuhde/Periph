#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <math.h>
#include "I2CConnectionZephyr.h"
#include "ADXL345.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define ADXL345_ADDR 0x53

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, ADXL345_ADDR);
    ADXL345Minimal accel(connection);                       // Create ADXL345 driver, (connection, spi=false)

    // --- 50-sample stationary tilt characterization at 10 Hz ---
    // With the sensor flat and the Z axis up, gravity should project entirely
    // onto Z. Tilting the board visibly redistributes the 1 *g* magnitude
    // across X and Y; the total vector magnitude stays near 1 *g*.
    const int SAMPLES = 50;
    const int PERIOD_MS = 100;

    float mag_min = 1e9f, mag_max = -1e9f;

    for (int n = 0; n < SAMPLES; n++) {
        float x, y, z;
        accel.read(x, y, z);                                // Read 3-axis acceleration, (x, y, z) → g, g, g
        float mag = sqrtf(x * x + y * y + z * z);
        if (mag < mag_min) mag_min = mag;
        if (mag > mag_max) mag_max = mag;
        printk("%2d  x=%+.3f  y=%+.3f  z=%+.3f  |a|=%.3f g\n",
               n, (double)x, (double)y, (double)z, (double)mag);
        k_sleep(K_MSEC(PERIOD_MS));
    }

    printk("min |a|=%.3f g  max |a|=%.3f g\n", (double)mag_min, (double)mag_max);
    return 0;
}