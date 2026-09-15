#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "LPS22DF.h"

#ifndef LPS22DF_I2C_NODE
#define LPS22DF_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef LPS22DF_ADDR
#define LPS22DF_ADDR 0x5C
#endif

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(LPS22DF_I2C_NODE);
    I2CConnectionZephyr connection(dev, LPS22DF_ADDR);
    LPS22DFMinimal lps(connection);

    float t = lps.temperature();
    check_true(t >= -40.0f && t <= 85.0f, "temperature_range");

    float p = lps.pressure();
    check_true(p >= 26000.0f && p <= 126000.0f, "pressure_range");

    LPS22DFFull lps_full(connection);
    lps_full.configure(3, 0, true, 1, true);
    float p2 = lps_full.pressure();
    check_true(p2 >= 26000.0f && p2 <= 126000.0f, "configure_then_read");

    float alt = lps_full.altitude(101325.0f);
    check_true(alt >= -500.0f && alt <= 10000.0f, "altitude");

    check_true(lps_full.who_am_i() == 0xB4, "who_am_i");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}