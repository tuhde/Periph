#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "Htu31d.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define HTU31D_ADDR 0x40

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, HTU31D_ADDR);
    HTU31DMinimal sensor(connection);                               // Create HTU31D driver, (connection) → void
    sensor.init();                                                  // Soft reset, () → void
    while (true) {
        float t = 0, rh = 0;
        bool ok = sensor.read(t, rh);                               // Read temperature and humidity, (temperature_c&, humidity_pct&) → bool
        printk("temperature %.2f C humidity %.1f %%RH (ok=%lu)\n", (double)(t), (double)(rh), (unsigned long)(ok));
        k_sleep(K_MSEC(2000));
    }
    return 0;
}
