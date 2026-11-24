#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "Hts221.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define HTS221_ADDR 0x5F

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, HTS221_ADDR);
    HTS221Minimal sensor(connection);                               // Create HTS221 driver, (connection) → void
    bool ok = sensor.init();                                        // Read calibration and start one-shot mode, () → bool
    float t = 0, rh = 0;

    while (true) {
        ok = sensor.read(t, rh);                                    // Read temperature and humidity, (temperature_c, humidity_pct) → bool
        printk("temperature %.2f C humidity %.1f %%rH (ok=%lu)\n", (double)(t), (double)(rh), (unsigned long)(ok));
        k_sleep(K_MSEC(2000));
    }
    return 0;
}
