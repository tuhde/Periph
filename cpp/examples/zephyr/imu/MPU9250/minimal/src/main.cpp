#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <stdio.h>
#include "I2CConnectionZephyr.h"
#include "MPU9250.h"

#ifndef MPU9250_I2C_NODE
#define MPU9250_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef MPU9250_ADDR
#define MPU9250_ADDR 0x68
#endif

int main(void) {
    const struct device *dev = DEVICE_DT_GET(MPU9250_I2C_NODE);
    I2CConnectionZephyr connection(dev, MPU9250_ADDR);
    MPU9250Minimal imu(connection);

    printk("MPU9250 minimal example started\n");

    while (1) {
        float ax, ay, az, gx, gy, gz;
        imu.accel(ax, ay, az);                            // Read 3-axis acceleration, (float&, float&, float&) → void m/s²
        imu.gyro(gx, gy, gz);                             // Read 3-axis angular rate, (float&, float&, float&) → void rad/s
        printk("accel: %.2f %.2f %.2f  gyro: %.2f %.2f %.2f\n",
               (double)ax, (double)ay, (double)az, (double)gx, (double)gy, (double)gz);
        k_sleep(K_MSEC(100));
    }
    return 0;
}