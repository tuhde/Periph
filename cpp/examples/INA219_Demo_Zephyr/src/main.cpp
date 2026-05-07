#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "INA219.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define INA219_ADDR 0x40

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CTransportZephyr transport(i2c_dev, INA219_ADDR);
    INA219Full ina(transport);

    printk("V          A          W\n");

    while (1) {
        while (!ina.conversion_ready()) {}

        printk("%.3fV   %.4fA   %.4fW\n",
               (double)ina.voltage(),
               (double)ina.current(),
               (double)ina.power());

        k_sleep(K_SECONDS(1));
    }
    return 0;
}
