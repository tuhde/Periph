#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "BME280.h"

#ifndef BME280_I2C_NODE
#define BME280_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef BME280_ADDR
#define BME280_ADDR 0x76
#endif

int main(void) {
    const struct device *dev = DEVICE_DT_GET(BME280_I2C_NODE);
    I2CTransportZephyr transport(dev, BME280_ADDR);
    BME280Minimal bme(transport);                       // Create BME280 driver, (transport, spi=false)

    for (int i = 0; i < 5; i++) {
        float t = bme.temperature();                    // Read temperature, () → float °C
        float p = bme.pressure();                      // Read pressure, () → float hPa
        float h = bme.humidity();                      // Read humidity, () → float %RH
        printk("%d C, %d hPa, %d %%RH\n", (int)t, (int)p, (int)h);
        k_msleep(1000);
    }

    printk("===DONE: 0 passed, 0 failed===\n");
    return 0;
}
