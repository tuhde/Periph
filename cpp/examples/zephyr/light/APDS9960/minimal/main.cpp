#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "APDS9960.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define APDS9960_ADDR 0x39

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, APDS9960_ADDR);
    APDS9960Minimal apds(connection);                       // Create APDS9960 driver, (connection) → APDS9960Minimal

    while (1) {
        uint16_t c, r, g, b;
        apds.color(c, r, g, b);                            // Read all RGBC channels, (clear, red, green, blue) → void
        printk("C=%u R=%u G=%u B=%u\n", c, r, g, b);
        k_sleep(K_SECONDS(1));
    }
    return 0;
}
