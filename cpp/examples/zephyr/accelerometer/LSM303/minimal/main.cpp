#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <math.h>
#include "I2CConnectionZephyr.h"
#include "LSM303.h"

#ifndef LSM303_I2C_NODE
#define LSM303_I2C_NODE DT_NODELABEL(i2c0)
#endif

#define LOG(...) printk(__VA_ARGS__)
static void lsm303_sleep_ms(uint32_t ms) { k_sleep(K_MSEC(ms)); }
static uint32_t lsm303_now_ms() { return (uint32_t)k_uptime_get(); }
static void on_int(uint16_t status) { printk("interrupt status: 0x%04X\n", status); }

int main(void) {
    const struct device *dev = DEVICE_DT_GET(LSM303_I2C_NODE);
    I2CConnectionZephyr accel_connection(dev, 0x19);
    I2CConnectionZephyr mag_connection(dev, 0x1E);
    LSM303Minimal compass(accel_connection, mag_connection);
    for (int i = 0; i < 10; i++) {
        float ax, ay, az, mx, my, mz;
        compass.read_acceleration(ax, ay, az);              // Read 3-axis acceleration, (x, y, z) -> g, g, g
        compass.read_magnetic(mx, my, mz);                  // Read 3-axis magnetic field, (x, y, z) -> uT, uT, uT
        LOG("a=(%.3f, %.3f, %.3f) g  m=(%.1f, %.1f, %.1f) uT\n", ax, ay, az, mx, my, mz);
        lsm303_sleep_ms(100);
    }
    return 0;
}
