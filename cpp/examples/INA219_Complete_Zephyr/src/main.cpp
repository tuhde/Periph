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

    Serial.println(ina.voltage());
    Serial.println(ina.shunt_voltage());
    Serial.println(ina.current());
    Serial.println(ina.power());
    Serial.println(ina.conversion_ready());
    Serial.println(ina.overflow());

    ina.configure(1, 3, 3, 3, 7);

    ina.shutdown();
    k_sleep(K_MSEC(1));
    ina.wake();

    ina.reset();

    while (1) {
        k_sleep(K_SECONDS(1));
    }
    return 0;
}
