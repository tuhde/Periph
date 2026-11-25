#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "VL6180X.h"

#define I2C_NODE DT_NODELABEL(i2c0)

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, VL6180XMinimal::I2C_ADDRESS, nullptr, nullptr, 2);
    VL6180XMinimal sensor(connection);                      // Create VL6180X driver, (connection)
    sensor.init();                                          // Initialize, () → bool

    while (1) {
        uint8_t mm = 0;
        if (sensor.read_range(mm)) {                        // Single-shot range, (uint8_t& mm) → bool
            printk("%u mm\n", (unsigned)mm);
        } else {
            printk("range error %u\n", (unsigned)sensor.last_error()); // Last range error code, () → uint8_t
        }
        k_msleep(200);
    }
    return 0;
}
