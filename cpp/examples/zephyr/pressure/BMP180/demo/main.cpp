#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "BMP180.h"

#ifndef BMP180_I2C_NODE
#define BMP180_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef BMP180_ADDR
#define BMP180_ADDR 0x77
#endif

int main(void) {
    const struct device *dev = DEVICE_DT_GET(BMP180_I2C_NODE);
    I2CTransportZephyr transport(dev, BMP180_ADDR);
    BMP180Full bmp(transport, BMP180Full.OSS_ULP);   // Create BMP180 driver, (transport, oss=0 ULP)

    float t0 = bmp.temperature();                      // Read temperature, () → float C
    float p0 = bmp.pressure();                       // Read pressure, () → float hPa
    float alt_ref = bmp.altitude();                 // Compute altitude, (sea_level_hpa=1013.25) → float m
    printk("Reference: %.1f C, %.1f hPa, alt=%.1f m\n", t0, p0, alt_ref);

    float prev_alt = 0.0f;
    for (int n = 0; n < 60; n++) {
        float t = bmp.temperature();                 // Read temperature, () → float C
        float p = bmp.pressure();                  // Read pressure, () → float hPa
        float a = bmp.altitude();                  // Compute altitude, (sea_level_hpa=1013.25) → float m
        float da = (a - prev_alt) * 100.0f;

        if (n > 0) {
            printk("%ds: %.1f C, %.1f hPa, alt=%.1f m (delta=%.0f cm)\n", n, t, p, a, da);
        } else {
            printk("%ds: %.1f C, %.1f hPa, alt=%.1f m\n", n, t, p, a);
        }
        prev_alt = a;
        k_sleep(K_SECONDS(1));
    }

    return 0;
}
