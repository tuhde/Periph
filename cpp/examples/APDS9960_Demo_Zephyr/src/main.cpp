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

    // --- Monitor ambient light with adaptive integration time ---
    // Start with the default 200 ms integration (ATIME=0xB6). When the clear
    // channel approaches saturation, halve the integration time to prevent overflow.
    apds.configure_als(0xB6, 1);                           // Configure ALS, (atime 0-255, again 0-3) → void

    while (1) {
        while (!apds.is_als_valid()) {                     // Check ALS data valid, () → bool
            k_sleep(K_MSEC(10));
        }

        uint16_t c, r, g, b;
        apds.color(c, r, g, b);                            // Read all RGBC channels, (clear, red, green, blue) → void
        float lux = -0.32466f * r + 1.57837f * g + -0.73191f * b;
        printk("C=%u R=%u G=%u B=%u  lux~%.0f\n", c, r, g, b, (double)lux);

        // --- Adaptive integration: reduce time when saturated ---
        if (apds.is_als_saturated()) {                     // Check ALS saturated, () → bool
            printk("[SATURATED — reducing integration time]\n");
            apds.configure_als(0xFE, 1);                   // Configure ALS, (atime 0-255, again 0-3) → void
            k_sleep(K_MSEC(250));
        }

        k_sleep(K_SECONDS(1));
    }
    return 0;
}
