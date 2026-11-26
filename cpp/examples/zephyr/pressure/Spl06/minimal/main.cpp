#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <math.h>
#include "I2CConnectionZephyr.h"
#include "Spl06.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define SPL06_ADDR 0x77

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, SPL06_ADDR);
    SPL06Minimal sensor(connection);                               // Create SPL06 driver, (connection) → void
    sensor.init();                                                 // Check ID, read coefficients, apply defaults, () → bool
    while (true) {
        float t = 0, p = 0;
        bool ok = sensor.read(t, p);                               // Measure temperature and pressure, (temperature_c&, pressure_pa&) → bool
        printk("pressure %.2f Pa temperature %.2f C (ok=%lu)\n", (double)(p), (double)(t), (unsigned long)(ok));
        k_sleep(K_MSEC(1000));
    }
    return 0;
}
