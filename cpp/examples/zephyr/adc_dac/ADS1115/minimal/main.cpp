#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "ADS1115.h"

#define I2C_NODE DT_NODELABEL(i2c0)

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, ADS1115Minimal::I2C_ADDRESS);
    ADS1115Minimal adc(connection);                         // Create ADS1115 driver, (connection)

    while (1) {
        float v = adc.readVoltage(0);                       // Read voltage, (channel=0) → float V
        printk("%.5f V\n", (double)v);
        k_msleep(1000);
    }
    return 0;
}
