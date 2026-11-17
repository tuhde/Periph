#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "LTC4151.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define LTC4151_ADDR 0x6A

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, LTC4151_ADDR);
    LTC4151Minimal ltc(connection, 0.02f);                              // Create LTC4151 driver, (connection, r_sense=0.02 Ω) → void
    while (1) {
        printk("V=%.3f V\n", (double)(ltc.voltage()));                  // Read supply voltage, () → float V
        printk("I=%.4f A\n", (double)(ltc.current()));                  // Read load current, () → float A
        printk("P=%.3f W\n", (double)(ltc.power()));                    // Read load power, () → float W
        k_sleep(K_MSEC(1000));
    }
    return 0;
}
