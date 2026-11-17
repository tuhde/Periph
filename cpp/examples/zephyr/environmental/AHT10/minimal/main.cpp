#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "AHT10.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define AHT10_ADDR 0x38

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, AHT10_ADDR);
    AHT10Minimal aht(connection);                                       // Create AHT10 driver, (connection, addr=0x38) → void

    while (1) {
        float t, h;
        aht.read(t, h);                                                // Trigger measurement, (temperature_c, humidity_pct) → void
        printk("Temperature: %.2f C\n", (double)t);
        printk("Humidity:    %.2f %%RH\n", (double)h);
        k_sleep(K_SECONDS(1));
    }
    return 0;
}
