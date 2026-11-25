#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "INA260.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define INA260_ADDR 0x40

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, INA260_ADDR);
    INA260Full ina(connection);

    ina.configure(3, 4, 4, 7);

    printk("Voltage:       %.3f V\n",  (double)ina.voltage());
    printk("Current:       %.3f A\n",  (double)ina.current());
    printk("Power:         %.3f W\n",  (double)ina.power());
    printk("Conv ready:    %d\n",       ina.conversion_ready());
    printk("Overflow:      %d\n",       ina.overflow());

    ina.set_alert(INA260Full::BOL, 4.5f);
    ina.clear_alert();
    ina.trigger(INA260Full::MODE_SHUNT_BUS_TRIGGERED);
    printk("Alert flags:   0x%04X\n",  ina.alert_flags());

    printk("Mfr ID:        0x%04X\n",  ina.manufacturer_id());
    printk("Die ID:        0x%04X\n",  ina.die_id());

    ina.shutdown();
    k_sleep(K_MSEC(10));
    ina.wake();

    ina.reset();
    return 0;
}
