#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "BMP085.h"

#ifndef BMP085_I2C_NODE
#define BMP085_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef BMP085_ADDR
#define BMP085_ADDR 0x77
#endif

int main(void) {
    const struct device *dev = DEVICE_DT_GET(BMP085_I2C_NODE);
    I2CConnectionZephyr connection(dev, BMP085_ADDR);
    BMP085Full bmp(connection, BMP085Full.OSS_ULP);   // Create BMP085 driver, (connection, oss=0 ULP)

    float t0 = bmp.temperature();                      // Read temperature, () → float C
    float p0 = bmp.pressure();                       // Read pressure, () → float Pa
    float alt_ref = bmp.altitude();                 // Compute altitude, (sea_level_pa=101325.0) → float m
    printk("Reference: %.1f C, %.1f Pa, alt=%.1f m\n", t0, p0, alt_ref);

    float prev_alt = 0.0f;
    for (int n = 0; n < 60; n++) {
        float t = bmp.temperature();                 // Read temperature, () → float C
        float p = bmp.pressure();                  // Read pressure, () → float Pa
        float a = bmp.altitude();                  // Compute altitude, (sea_level_pa=101325.0) → float m
        float da = (a - prev_alt) * 100.0f;

        if (n > 0) {
            printk("%ds: %.1f C, %.1f Pa, alt=%.1f m (delta=%.0f cm)\n", n, t, p, a, da);
        } else {
            printk("%ds: %.1f C, %.1f Pa, alt=%.1f m\n", n, t, p, a);
        }
        prev_alt = a;
        k_sleep(K_SECONDS(1));
    }

    return 0;
}