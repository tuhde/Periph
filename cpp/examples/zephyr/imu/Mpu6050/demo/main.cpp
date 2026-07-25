#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <cmath>
#include "I2CTransportZephyr.h"
#include "Mpu6050.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define MPU6050_ADDR 0x68

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CTransportZephyr transport(i2c_dev, MPU6050_ADDR);

    // --- Configure for motion logging with moderate dynamic range ---
    // ±4g captures typical tilting and handling forces without clipping;
    // ±500 dps covers fast rotations while retaining sub-degree resolution.
    MPU6050Full imu(transport);                          // Create MPU6050 driver, (transport, addr=0x68) → None
    imu.configure_accel(1);                               // Configure accel range, (full_scale=0) → None
    imu.configure_gyro(1);                                // Configure gyro range, (full_scale=0) → None

    printk("roll     pitch    |accel|    |gyro|\n");

    while (1) {
        // gate reads on data_ready so each sample reflects a fresh conversion
        while (!imu.data_ready()) {}                      // Check data ready flag, () → bool

        float ax, ay, az, gx, gy, gz;
        imu.accel(ax, ay, az);                            // Read 3-axis acceleration, (x, y, z) → m/s²
        imu.gyro(gx, gy, gz);                             // Read 3-axis angular rate, (x, y, z) → rad/s

        // --- Compute tilt angles from the accelerometer gravity vector ---
        float roll  = atan2f(ay, az) * 180.0f / 3.141592653589793f;
        float pitch = atan2f(-ax, sqrtf(ay * ay + az * az)) * 180.0f / 3.141592653589793f;
        float accel_mag = sqrtf(ax * ax + ay * ay + az * az);
        float gyro_mag  = sqrtf(gx * gx + gy * gy + gz * gz);

        printk("%-8.1f %-8.1f %-10.3f %-10.3f\n",
               (double)roll, (double)pitch, (double)accel_mag, (double)gyro_mag);

        k_sleep(K_MSEC(100));
    }
    return 0;
}
