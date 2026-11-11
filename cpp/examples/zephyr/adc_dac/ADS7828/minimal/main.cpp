#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "ADS7828.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define ADS7828_ADDR 0x48

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, ADS7828_ADDR);
    ADS7828Minimal adc(connection);                         // Create ADS7828 driver, (connection, vref=0 → internal 2.5 V)

    while (1) {
        uint16_t raw = adc.readRaw(0);                              // Read CH0 raw code, (channel=0–7) → uint16_t
        float v0 = adc.readVoltage(0);                              // Read CH0 voltage, (channel=0–7) → float V
        float all[8];
        adc.readAll(all);                                           // Read all eight channels, (out[8]) → void
        printk("raw=%u v0=%.3f V ch7=%.3f V\n", raw, (double)v0, (double)all[7]);
        k_sleep(K_SECONDS(1));
    }
    return 0;
}
