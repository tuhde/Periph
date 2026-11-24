#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "Ms5607.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define MS5607_ADDR 0x77

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, MS5607_ADDR);
    MS5607Minimal sensor(connection);                               // Create MS5607 driver, (connection) → void
    sensor.init();                                                  // Reset, read PROM, verify CRC-4, () → bool
    while (true) {
        float t = 0, p = 0;
        bool ok = sensor.read(t, p);                                // Read temperature and pressure, (temperature_c&, pressure_pa&) → bool
        printk("temperature %.2f C pressure %.1f Pa (ok=%lu)\n", (double)(t), (double)(p), (unsigned long)(ok));
        k_sleep(K_MSEC(1000));
    }
    return 0;
}
