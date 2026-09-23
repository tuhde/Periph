#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "MCP9808.h"

#define I2C_NODE DT_NODELABEL(i2c0)

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, MCP9808Minimal::I2C_ADDRESS);
    MCP9808Minimal sensor(connection);                      // Create MCP9808 driver, (connection)

    while (1) {
        float t = sensor.readTemperature();                     // Read ambient temperature, () → °C
        printk("%.4f C\n", (double)t);
        k_msleep(1000);
    }
    return 0;
}
