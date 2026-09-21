#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <stdio.h>
#include <math.h>
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
    I2CConnectionZephyr magConnection(dev, 0x0C);      // AK8963, same bus, reached via I²C bypass

    // --- Configure for tilt and heading estimation ---
    // ±4g / ±500dps trade sensitivity for headroom against sharper motion than
    // the ±2g / ±250dps defaults tolerate; 16-bit continuous magnetometer mode
    // keeps a fresh heading available on every poll.
    MPU9250Full imu(connection, magConnection);       // Create MPU9250 driver, (connection, magConnection) → void
    imu.configure_accel(1);                           // Configure accel range, (full_scale=0) → void
    imu.configure_gyro(1);                            // Configure gyro range, (full_scale=0) → void
    imu.enable_mag(16, 6);                            // Initialize magnetometer, (bits=16, mode=6) → void

    printk("roll     pitch    heading  |accel|  |gyro|\n");

    while (1) {
        // gate reads on data_ready so each sample reflects a fresh conversion
        while (!imu.data_ready()) {                   // Check data ready flag, () → bool
        }

        float ax, ay, az, gx, gy, gz, mx, my, mz;
        imu.accel(ax, ay, az);                        // Read 3-axis acceleration, (float&, float&, float&) → void m/s²
        imu.gyro(gx, gy, gz);                         // Read 3-axis angular rate, (float&, float&, float&) → void rad/s
        imu.mag(mx, my, mz);                          // Read 3-axis magnetic field, (float&, float&, float&) → void µT

        // --- Compute tilt angles from the accelerometer gravity vector ---
        // roll and pitch are reliable when the device is quasi-static;
        // gyro magnitude indicates how fast the board is being rotated.
        float roll  = atan2f(ay, az) * 180.0f / 3.141592653589793f;
        float pitch = atan2f(-ax, sqrtf(ay * ay + az * az)) * 180.0f / 3.141592653589793f;

        // --- Compute magnetic heading (simplified, no tilt compensation) ---
        // Magnetometer axes differ from accel/gyro axes; user must account for this in fusion.
        float heading = atan2f(my, mx) * 180.0f / 3.141592653589793f;

        float accel_mag = sqrtf(ax * ax + ay * ay + az * az);
        float gyro_mag  = sqrtf(gx * gx + gy * gy + gz * gz);

        printk("%.1f      %.1f      %.1f      %.3f    %.3f\n",
               (double)roll, (double)pitch, (double)heading, (double)accel_mag, (double)gyro_mag);
        k_sleep(K_MSEC(100));
    }
    return 0;
}