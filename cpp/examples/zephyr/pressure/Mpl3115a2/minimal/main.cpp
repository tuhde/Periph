#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "Mpl3115a2.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define MPL3115A2_ADDR 0x60

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, MPL3115A2_ADDR);
    MPL3115A2Minimal sensor(connection);                           // Create MPL3115A2 driver, (connection) → void
    sensor.init();                                                 // Reset and apply defaults, () → bool
    while (true) {
        float p = 0, t = 0;
        bool ok = sensor.read(p, t);                               // Read pressure and temperature, (pressure_pa&, temperature_c&) → bool
        printk("pressure %.2f Pa temperature %.2f C (ok=%lu)\n", (double)(p), (double)(t), (unsigned long)(ok));
        k_sleep(K_MSEC(2000));
    }
    return 0;
}
