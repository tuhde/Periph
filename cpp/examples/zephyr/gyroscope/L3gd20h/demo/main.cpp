#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/drivers/i2c.h>
#include <I2CConnectionZephyr.h>
#include <L3gd20h.h>
#include <stdio.h>
#include <math.h>

#ifndef L3GD20H_I2C_NODE
#define L3GD20H_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef L3GD20H_ADDR
#define L3GD20H_ADDR 0x6A
#endif

int main(void) {
    const struct device* i2c_dev = DEVICE_DT_GET(L3GD20H_I2C_NODE);
    if (!device_is_ready(i2c_dev)) {
        printf("I2C device not ready\n");
        return 0;
    }

    I2CConnectionZephyr conn(i2c_dev, L3GD20H_ADDR);
    L3gd20hFull gyro(conn);

    // --- Configure for shake detection at 190 Hz, ±500 dps ---
    // 190 Hz ODR provides good temporal resolution for shake detection;
    // ±500 dps full scale gives 17.5 mdps/digit sensitivity, suitable for
    // detecting moderate to strong motion without clipping.
    gyro.configure(L3gd20hFull::ODR_190_HZ, 0, 1);  // Configure, (odr 0-3, bw 0-3, full_scale 0-2) -> None

    printf("L3GD20H shake detector running. Shake the device...\n");

    while (1) {
        if (gyro.data_ready()) {                       // Check data ready, () -> bool
            float x, y, z;
            gyro.gyro(x, y, z);                        // Read angular rate, () -> (float, float, float) rad/s
            float magnitude = sqrtf(x*x + y*y + z*z);
            if (magnitude > 1.0) {
                printf("SHAKE DETECTED: mag=%.3f (x=%.3f y=%.3f z=%.3f)\n", magnitude, x, y, z);
            } else {
                printf("x=%.3f y=%.3f z=%.3f mag=%.3f\n", x, y, z, magnitude);
            }
        }
    }
    return 0;
}