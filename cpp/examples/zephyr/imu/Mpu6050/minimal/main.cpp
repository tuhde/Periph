#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "Mpu6050.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define MPU6050_ADDR 0x68

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CTransportZephyr transport(i2c_dev, MPU6050_ADDR);
    MPU6050Minimal imu(transport);                       // Create MPU6050 driver, (transport, addr=0x68) → None

    while (1) {
        float ax, ay, az;
        float gx, gy, gz;
        imu.accel(ax, ay, az);                           // Read 3-axis acceleration, (x, y, z) → m/s²
        imu.gyro(gx, gy, gz);                            // Read 3-axis angular rate, (x, y, z) → rad/s
        printk("accel: %.2f %.2f %.2f  gyro: %.2f %.2f %.2f\n",
               (double)ax, (double)ay, (double)az,
               (double)gx, (double)gy, (double)gz);
        k_sleep(K_MSEC(100));
    }
    return 0;
}
