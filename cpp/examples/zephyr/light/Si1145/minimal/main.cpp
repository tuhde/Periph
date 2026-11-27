#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "Si1145.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define SI1145_ADDR 0x60

static unsigned uv_whole(float uv) { return (unsigned)(uv * 100.0f + 0.5f) / 100; }
static unsigned uv_frac(float uv) { return (unsigned)(uv * 100.0f + 0.5f) % 100; }

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, SI1145_ADDR);
    Si1145Minimal sensor(connection);                              // Create Si1145 driver, (connection) → void
    sensor.init();                                                 // Check PART_ID, HW_KEY, UV + ALS + PS1, () → bool
    while (true) {
        Si1145Reading r;
        bool ok = sensor.read(r);                                  // Forced PS + ALS, (reading&) → bool
        printk("prox %u UV %u.%02u vis %u ir %u (ok=%u)\n", (unsigned)r.proximity, uv_whole(r.uv_index),
              uv_frac(r.uv_index), (unsigned)r.visible, (unsigned)r.infrared, (unsigned)ok);
        k_msleep(1000);
    }
    return 0;
}
