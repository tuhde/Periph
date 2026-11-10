#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "QMI8658A.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define QMI8658A_ADDR 0x6B

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, QMI8658A_ADDR);
    QMI8658AMinimal imu(connection);                       // Create QMI8658A driver, (connection) → void

    while (true) {
        float ax, ay, az, gx, gy, gz;
        imu.accel(ax, ay, az);                        // Read 3-axis acceleration, (float&, float&, float&) → void m/s²
        imu.gyro(gx, gy, gz);                         // Read 3-axis angular rate, (float&, float&, float&) → void rad/s
        printk("accel: %.2f %.2f %.2f  gyro: %.2f %.2f %.2f\n", (double)(ax), (double)(ay), (double)(az), (double)(gx), (double)(gy), (double)(gz));
        k_sleep(K_MSEC(100));
    }
    return 0;
}
