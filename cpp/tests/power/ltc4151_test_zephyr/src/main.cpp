#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "LTC4151.h"

#ifndef LTC4151_I2C_NODE
#define LTC4151_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef LTC4151_ADDR
#define LTC4151_ADDR 0x6A
#endif

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

static void check_near(float val, float lo, float hi, const char *label) {
    if (val >= lo && val <= hi) { printk("PASS %s\n", label); passed++; }
    else { printk("FAIL %s: %.4f not in [%.4f, %.4f]\n",
                  label, (double)val, (double)lo, (double)hi); failed++; }
}

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(LTC4151_I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, LTC4151_ADDR);
    LTC4151Full ltc(connection);

    check_near(ltc.voltage(), 7.0f, 80.0f, "voltage range");
    check_near(ltc.current(), 0.0f, 4.096f, "current range");
    check_near(ltc.power(), 0.0f, 330.0f, "power range");
    check_near(ltc.sense_voltage(), 0.0f, 0.08192f, "sense voltage range");
    check_near(ltc.adin_voltage(), 0.0f, 2.048f, "adin range");
    LTC4151Full::Readings r;
    ltc.read_all(r);
    check_near(r.vin, 7.0f, 80.0f, "read_all vin");
    check_true(ltc.read_raw(LTC4151Full::VIN) > 0 && ltc.read_raw(LTC4151Full::VIN) < 4096, "read_raw vin");
    check_true(ltc.read_control() == 0x0C, "control default");
    check_true(!ltc.is_snapshot_mode(), "not snapshot mode");
    float s = 0;
    check_true(ltc.snapshot(LTC4151Full::VIN, s) && s >= 7.0f && s <= 80.0f, "snapshot vin");
    check_true(!ltc.is_snapshot_mode(), "back in continuous");
    ltc.set_stuck_bus_timer(false);
    check_true(ltc.read_control() == 0x08, "stuck-bus timer off");
    ltc.set_stuck_bus_timer(true);
    check_true(ltc.read_control() == 0x0C, "stuck-bus timer on");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
