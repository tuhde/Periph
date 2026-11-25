#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "ADS1014.h"

#define I2C_NODE DT_NODELABEL(i2c0)

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, ADS1014Minimal::I2C_ADDRESS);
    ADS1014Minimal adc(connection);                     // Create ADS1014 driver, (connection)

    while (1) {
        int16_t raw = adc.readRaw();                    // Read raw code, () → int16 (−2048…2047)
        float v = adc.readVoltage();                    // Read voltage, () → V
        printk("%d -> %.3f V\n", (int)raw, (double)v);
        k_msleep(1000);
    }
    return 0;
}
