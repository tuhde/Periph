#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "Bh1750.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define BH1750_ADDR 0x23

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, BH1750_ADDR);
    BH1750Minimal sensor(connection);                               // Create BH1750 driver, (connection) → void
    sensor.init();                                                  // Power on, () → void
    while (true) {
        float lux = 0;
        bool ok = sensor.read(lux);                                 // Measure illuminance, (lux&) → bool
        printk("illuminance %.1f lx (ok=%lu)\n", (double)(lux), (unsigned long)(ok));
        k_sleep(K_MSEC(1000));
    }
    return 0;
}
