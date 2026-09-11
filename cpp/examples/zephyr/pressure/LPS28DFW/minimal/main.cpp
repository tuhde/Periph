#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "LPS28DFW.h"

#ifndef LPS28DFW_I2C_NODE
#define LPS28DFW_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef LPS28DFW_ADDR
#define LPS28DFW_ADDR 0x5C
#endif

int main(void) {
    const struct device *dev = DEVICE_DT_GET(LPS28DFW_I2C_NODE);
    I2CConnectionZephyr connection(dev, LPS28DFW_ADDR);
    LPS28DFWMinimal lps(connection);                            // Create LPS28DFW driver, (connection)

    for (int i = 0; i < 5; i++) {
        float t = lps.read_temperature();                       // Read temperature, () → float °C
        float p = lps.read_pressure();                          // Read pressure, () → float hPa
        printk("%.1f C, %.1f hPa\n", (double)t, (double)p);
        k_sleep(K_SECONDS(1));
    }

    printk("===DONE: 0 passed, 0 failed===\n");
    return 0;
}