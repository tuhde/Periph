#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "HTU21DF.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define HTU21DF_ADDR 0x40

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, HTU21DF_ADDR);
    HTU21DFMinimal sensor(connection);                                  // Create HTU21D-F driver, (connection) → void
    sensor.init();                                                      // Soft reset, () → void
    float t = 0, rh = 0;

    while (true) {
        bool ok = sensor.read(t, rh);                                   // Read temperature and humidity, (t, rh) → bool °C, %RH
        printk("%.2f C, %.1f %%RH (ok=%u)\n", (double)(t), (double)(rh), (unsigned)ok);
        k_sleep(K_MSEC(2000));
    }
    return 0;
}
