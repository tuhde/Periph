#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "LPS22DF.h"

#ifndef LPS22DF_I2C_NODE
#define LPS22DF_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef LPS22DF_ADDR
#define LPS22DF_ADDR 0x5C
#endif

int main(void) {
    const struct device *dev = DEVICE_DT_GET(LPS22DF_I2C_NODE);
    I2CConnectionZephyr connection(dev, LPS22DF_ADDR);
    LPS22DFMinimal lps(connection);                        // Create LPS22DF driver, (connection, spi=false)

    for (int i = 0; i < 5; i++) {
        float p = lps.pressure();                         // Read pressure, () → float Pa
        float t = lps.temperature();                      // Read temperature, () → float °C
        printk("%.1f C, %.0f Pa\n", t, p);
        k_sleep(K_SECONDS(1));
    }
    printk("===DONE: 0 passed, 0 failed===\n");
    return 0;
}