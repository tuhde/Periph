#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "Si1133.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define SI1133_ADDR 0x55

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, SI1133_ADDR);
    Si1133Minimal sensor(connection);                              // Create Si1133 driver, (connection) → void
    sensor.init();                                                 // Check PART_ID, reset, UV + white channels, () → bool
    while (true) {
        int32_t uv = 0, white = 0;
        bool ok = sensor.read(uv, white);                          // Forced measurement, (uv&, white&) → bool
        printk("UV %ld white %ld (ok=%u)\n", (long)(uv), (long)(white), (unsigned)(ok));
        k_msleep(1000);
    }
    return 0;
}
