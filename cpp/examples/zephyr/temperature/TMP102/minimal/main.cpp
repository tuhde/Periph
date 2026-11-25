#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "TMP102.h"

#define I2C_NODE DT_NODELABEL(i2c0)

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, TMP102Minimal::I2C_ADDRESS);
    TMP102Minimal sensor(connection);                       // Create TMP102 driver, (connection)

    while (1) {
        float t = sensor.readTemperature();                     // Read temperature, () → °C
        printk("%.4f C\n", (double)t);
        k_msleep(1000);
    }
    return 0;
}
