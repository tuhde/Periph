#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "VL53L0X.h"

#define I2C_NODE DT_NODELABEL(i2c0)

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, VL53L0XMinimal::I2C_ADDRESS);
    VL53L0XMinimal sensor(connection);                      // Create VL53L0X driver, (connection)

    while (1) {
        uint16_t d = sensor.distance();                     // Measure distance, () → uint16_t mm
        if (sensor.rangeValid()) {                          // Check last measurement, () → bool
            printk("%u mm\n", (unsigned)d);
        } else {
            printk("out of range\n");
        }
        k_msleep(100);
    }
    return 0;
}
