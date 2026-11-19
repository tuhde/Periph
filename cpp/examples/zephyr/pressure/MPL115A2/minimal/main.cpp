#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "MPL115A2.h"

#ifndef MPL115A2_I2C_NODE
#define MPL115A2_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef MPL115A2_ADDR
#define MPL115A2_ADDR 0x60
#endif

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(MPL115A2_I2C_NODE);
    I2CConnectionZephyr connection(dev, MPL115A2_ADDR);
    MPL115A2Minimal baro(connection);                   // Create MPL115A2 driver, (connection)

    for (int i = 0; i < 5; i++) {
        float p = baro.read_pressure();                   // Read pressure, () → float Pa
        float t = baro.read_temperature();                // Read temperature, () → float °C (approximate)
        printk("%.0f Pa, %.1f C\n", p, t);
        k_msleep(1000);
    }

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
