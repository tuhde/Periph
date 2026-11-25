#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "Si7021.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define Si7021_ADDR 0x40

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, Si7021_ADDR);
    Si7021Minimal sensor(connection);                                   // Create Si7021 driver, (connection) → void
    float t = 0, rh = 0;

    while (true) {
        bool ok = sensor.read(t, rh);                                   // Read temperature and humidity, (t, rh) → bool °C, %RH
        printk("%.2f C, %.1f %%RH (ok=%u)\n", (double)(t), (double)(rh), (unsigned)ok);
        k_sleep(K_MSEC(2000));
    }
    return 0;
}
