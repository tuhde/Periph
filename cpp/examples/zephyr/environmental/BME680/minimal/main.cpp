#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "BME680.h"

#ifndef BME680_I2C_NODE
#define BME680_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef BME680_ADDR
#define BME680_ADDR 0x76
#endif

int main(void) {
    const struct device *dev = DEVICE_DT_GET(BME680_I2C_NODE);
    I2CTransportZephyr transport(dev, BME680_ADDR);
    BME680Minimal bme(transport);                        // Create BME680 driver, (transport)

    for (int i = 0; i < 5; i++) {
        float t = bme.temperature();                     // Read temperature, () → float °C
        float p = bme.pressure();                       // Read pressure, () → float hPa
        float h = bme.humidity();                       // Read humidity, () → float %RH
        float g = bme.gas_resistance();                 // Read gas resistance, () → float Ω
        printk("%.1f C, %.1f hPa, %.1f %%RH, %.0f Ohm\n", t, p, h, (double)g);
        k_sleep(K_SECONDS(5));
    }

    printk("===DONE: 0 passed, 0 failed===\n");
    return 0;
}
