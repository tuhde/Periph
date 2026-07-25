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

    printk("Voltage: %.3f V\n", (double)ina.voltage());
    printk("Shunt:   %.6f V\n", (double)ina.shunt_voltage());
    printk("Current: %.4f A\n", (double)ina.current());
    printk("Power:   %.4f W\n", (double)ina.power());
    printk("Conv Ready: %d\n", ina.conversion_ready());
    printk("Overflow:   %d\n", ina.overflow());

    ina.configure(1, 3, 0x03, 0x03, 7);

    ina.shutdown();
    k_sleep(K_MSEC(1));
    ina.wake();

    ina.reset();

    return 0;
}