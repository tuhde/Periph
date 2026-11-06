#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "AD5248.h"

#ifndef AD5248_I2C_NODE
#define AD5248_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef AD5248_ADDR
#define AD5248_ADDR 0x2C
#endif

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(AD5248_I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, AD5248_ADDR);
    AD5248Full pot(connection);

    pot.reset();
    pot.set_wiper(0, 100);
    check_true(pot.get_wiper(0) == 100, "set/get wiper ch0");
    pot.set_wiper(1, 200);
    check_true(pot.get_wiper(1) == 200, "set/get wiper ch1");
    check_true(pot.get_wiper(0) == 100, "ch0 unchanged by ch1 write");
    pot.set_resistance(0, 5320.0f);
    check_true(pot.get_wiper(0) == 128, "set_resistance midscale code");
    check_true(pot.get_resistance(0) > 5319.0f && pot.get_resistance(0) < 5321.0f, "get_resistance ~5320");
    pot.set_both(10, 20);
    check_true(pot.get_wiper(0) == 10, "set_both ch0");
    check_true(pot.get_wiper(1) == 20, "set_both ch1");
    pot.set_ratio(0, 0.5f);
    check_true(pot.get_ratio(0) > 0.49f && pot.get_ratio(0) < 0.51f, "get_ratio ~0.5");
    pot.set_wiper(0, 250);
    check_true(pot.increment(0, 10) == 255, "increment clamps");
    pot.set_wiper(1, 77);
    pot.shutdown(1);
    check_true(pot.is_shutdown(1), "is_shutdown");
    pot.resume(1);
    check_true(pot.get_wiper(1) == 77, "RDAC kept across shutdown");
    pot.reset();
    check_true(pot.get_wiper(0) == 0x80, "reset ch0");
    check_true(pot.get_wiper(1) == 0x80, "reset ch1");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
