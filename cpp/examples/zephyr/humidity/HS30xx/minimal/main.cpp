#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "HS30xx.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define HS30xx_ADDR 0x44

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, HS30xx_ADDR);
    HS30xxMinimal sensor(connection);                               // Create HS30xx driver, (connection) → void
    float t = 0, rh = 0;

    while (true) {
        bool ok = sensor.read(t, rh);                               // Read temperature and humidity, (t, rh) → bool °C, %RH
        printk("%.2f C, %.1f %%RH (ok=%lu)\n", (double)(t), (double)(rh), (unsigned long)(ok));
        k_sleep(K_MSEC(2000));
    }
    return 0;
}
