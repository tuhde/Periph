#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "LSM303.h"

#define I2C_NODE DT_NODELABEL(i2c0)

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr accelConn(i2c_dev, 0x19);           // Accelerometer connection at 0x19
    I2CConnectionZephyr magConn(i2c_dev, 0x1E);             // Magnetometer connection at 0x1E

    LSM303Minimal chip(accelConn, magConn);                 // Create LSM303 driver, (accel_conn, mag_conn) → sets ±2 g, 100 Hz, ±130 µT, 15 Hz

    while (1) {
        float ax, ay, az, mx, my, mz;
        chip.readAcceleration(ax, ay, az);                  // Read acceleration, (x, y, z) → g, g, g
        chip.readMagnetic(mx, my, mz);                      // Read magnetic field, (x, y, z) → µT, µT, µT
        printk("a=%.3f,%.3f,%.3f g  m=%.1f,%.1f,%.1f uT\n",
               (double)ax, (double)ay, (double)az,
               (double)mx, (double)my, (double)mz);
        k_sleep(K_MSEC(200));
    }
    return 0;
}
