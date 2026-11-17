#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "LM25066.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define LM25066_ADDR 0x40

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, LM25066_ADDR);
    LM25066Minimal hotswap(connection, 1.0f, false);   // Create LM25066 driver, (connection, rSenseMohm, currentLimitHigh)

    while (true) {
        printk("VIN %.3f V  VOUT %.3f V\n", (double)hotswap.voltage_in(), (double)hotswap.voltage_out()); // Read VIN / VOUT, () → float V each
        printk("IIN %.3f A  PIN %.2f W  T %.1f C\n", (double)hotswap.current(), (double)hotswap.power(), (double)hotswap.temperature()); // Read A / W / MOSFET °C, () → float each
        k_msleep(1000);
    }
    return 0;
}
