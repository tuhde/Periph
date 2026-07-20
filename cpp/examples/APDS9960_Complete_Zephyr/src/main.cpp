#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "APDS9960.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define APDS9960_ADDR 0x39

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CTransportZephyr transport(i2c_dev, APDS9960_ADDR);
    APDS9960Full apds(transport);                          // Create APDS9960 driver, (transport) → APDS9960Full

    apds.configure_als(0xB6, 1);                           // Configure ALS, (atime 0-255, again 0-3) → void
                                                           // sets integration time and gain for the ALS/color engine

    uint16_t c, r, g, b;
    apds.color(c, r, g, b);                                // Read all RGBC channels, (clear, red, green, blue) → void
                                                           // burst read 0x94-0x9B latches all channels atomically
    printk("C=%u R=%u G=%u B=%u\n", c, r, g, b);

    apds.enable_proximity(true);                           // Enable proximity engine, (enabled) → void
    k_sleep(K_MSEC(100));
    printk("Proximity: %u\n", apds.proximity());           // Read proximity count, () → uint8_t

    printk("ALS valid: %d\n", apds.is_als_valid());        // Check ALS data valid, () → bool
    printk("Prox valid: %d\n", apds.is_proximity_valid()); // Check proximity valid, () → bool
    printk("ALS sat: %d\n", apds.is_als_saturated());      // Check ALS saturated, () → bool
    printk("Chip ID: 0x%02X\n", apds.chip_id());           // Read device ID, () → uint8_t

    apds.enable_proximity(false);
    return 0;
}
