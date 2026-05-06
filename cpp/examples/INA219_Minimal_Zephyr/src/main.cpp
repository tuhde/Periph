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
    INA219Minimal ina(transport);

    while (1) {
        printk("Voltage: %.3f V\n", (double)ina.voltage());
        printk("Current: %.3f A\n", (double)ina.current());
        printk("Power:   %.3f W\n", (double)ina.power());
        k_sleep(K_SECONDS(1));
    }
    return 0;
}
