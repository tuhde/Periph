#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "BME688.h"

#ifndef BME688_I2C_NODE
#define BME688_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef BME688_ADDR
#define BME688_ADDR 0x76
#endif

int main(void) {
    const struct device *dev = DEVICE_DT_GET(BME688_I2C_NODE);
    I2CConnectionZephyr connection(dev, BME688_ADDR);
    BME688Minimal bme(connection);                        // Create BME688 driver, (connection)

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
