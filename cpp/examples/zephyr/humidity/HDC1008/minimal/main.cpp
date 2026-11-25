#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "HDC1008.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define HDC1008_ADDR 0x40

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, HDC1008_ADDR);
    HDC1008Minimal sensor(connection);                                  // Create HDC1008 driver, (connection) → void
    sensor.init();                                                      // Write default configuration, () → void
    float t = 0, rh = 0;

    while (true) {
        bool ok = sensor.read(t, rh);                                   // Read temperature and humidity, (t, rh) → bool °C, %RH
        printk("%.2f C, %.1f %%RH (ok=%u)\n", (double)(t), (double)(rh), (unsigned)ok);
        k_sleep(K_MSEC(2000));
    }
    return 0;
}
