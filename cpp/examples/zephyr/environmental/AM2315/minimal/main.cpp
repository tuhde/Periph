#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <math.h>
#include "I2CConnectionZephyr.h"
#include "AM2315.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define AM2315_ADDR 0x5C

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, AM2315_ADDR);
    AM2315Minimal sensor(connection);                                        // Create AM2315 driver, (connection) → void
    float t = 0, rh = 0;

    while (true) {
        bool ok = sensor.read(t, rh);                                            // Read temperature and humidity, (t, rh) → bool °C, %RH
                                                                                 // previous measurement; reads are kept >= 2 s apart
        printk("%.1f C, %.1f %%RH (ok=%u)\n", (double)(t), (double)(rh), (unsigned)(ok));
        k_sleep(K_MSEC(2000));
    }
    return 0;
}
