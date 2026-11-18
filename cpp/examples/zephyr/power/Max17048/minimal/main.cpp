#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "Max17048.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define MAX17048_ADDR 0x36

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, MAX17048_ADDR);
    Max17048Minimal gauge(connection, 1);                   // Create MAX17048 driver, (connection, cells)

    while (true) {
        printk("%.3f V  %.1f %%\n", (double)(gauge.voltage()), (double)(gauge.percent()));          // Read V / %, () → float each
        k_msleep(1000);
    }
    return 0;
}
