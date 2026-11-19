#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "As7262.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define AS7262_ADDR 0x49

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, AS7262_ADDR);
    AS7262Minimal sensor(connection);                               // Create AS7262 driver, (connection) → void
    AS7262Channels ch = {};
    if (!sensor.begin()) {                                          // Reset and configure defaults, () → bool
        printk("AS7262 not found\n");
    }

    while (true) {
        bool ok = sensor.read(ch);                                  // Read six calibrated channels, (ch) → bool
        printk("V %.1f B %.1f G %.1f Y %.1f O %.1f R %.1f (ok=%lu)\n", (double)(ch.violet), (double)(ch.blue), (double)(ch.green), (double)(ch.yellow), (double)(ch.orange), (double)(ch.red), (unsigned long)(ok));
        printk("%.0f C\n", (double)(sensor.temperature()));
        k_sleep(K_MSEC(2000));
    }
    return 0;
}
