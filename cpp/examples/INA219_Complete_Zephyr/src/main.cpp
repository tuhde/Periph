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

    printk("voltage: %.3f\n", (double)ina.voltage());
    printk("shunt_voltage: %.6f\n", (double)ina.shunt_voltage());
    printk("current: %.6f\n", (double)ina.current());
    printk("power: %.6f\n", (double)ina.power());
    printk("conversion_ready: %d\n", ina.conversion_ready());
    printk("overflow: %d\n", ina.overflow());

    ina.configure(1, 3, 3, 3, 7);

    ina.shutdown();
    k_sleep(K_MSEC(1));
    ina.wake();

    ina.trigger();

    ina.reset();

    return 0;
}
