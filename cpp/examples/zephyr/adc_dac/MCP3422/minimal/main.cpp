#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "MCP3422.h"

#define I2C_NODE DT_NODELABEL(i2c0)

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, MCP3422Minimal::I2C_ADDRESS);
    MCP3422Minimal adc(connection);                     // Create MCP3422 driver, (connection)
    for (int i = 0; i < 10; i++) {
        int32_t raw0 = adc.readRaw(0);                  // Read a signed code, (channel 0–1) → int32_t
        float v1 = adc.readVoltage(1);                  // Read the differential voltage, (channel 0–1) → float V
        printk("CH1 raw %d, CH2 %.6f V\n", (int)raw0, (double)v1);
        float both[2];
        bool ok = adc.readAll(both);                    // Read both channels, (float out[2]) → bool
        printk("all %d %.6f %.6f\n", (int)ok, (double)both[0], (double)both[1]);
        k_sleep(K_MSEC(1000));
    }
    return 0;
}
