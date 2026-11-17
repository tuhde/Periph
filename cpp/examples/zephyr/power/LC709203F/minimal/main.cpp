#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "LC709203F.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define LC709203F_ADDR 0x0B

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, LC709203F_ADDR);
    LC709203FMinimal gauge(connection, 1000, 1);                 // Create LC709203F driver, (connection, capacityMah, profile)

    while (true) {
        printk("%.3f V  %.1f %%  %.2f C\n",
               (double)gauge.voltage(), (double)gauge.percent(), (double)gauge.temperature());   // Read V / % / °C, () → float each
        k_msleep(5000);
    }
    return 0;
}
