#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "INA226.h"

#ifndef INA226_I2C_NODE
#define INA226_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef INA226_ADDR
#define INA226_ADDR 0x40
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
    const struct device *i2c_dev = DEVICE_DT_GET(INA226_I2C_NODE);
    I2CTransportZephyr transport(i2c_dev, INA226_ADDR);
    INA226Full ina(transport);

    check_true(ina.manufacturer_id() == 0x5449, "manufacturer_id");
    check_true(ina.die_id() == 0x2260,          "die_id");

    check_near(ina.voltage(),       0.0f, 40.0f,  "voltage range");
    check_near(ina.shunt_voltage(), -0.1f, 0.1f,  "shunt_voltage range");
    check_near(ina.current(),       -2.0f, 2.0f,  "current range");
    check_near(ina.power(),          0.0f, 80.0f, "power range");

    check_true(!ina.overflow(),   "no overflow");

    ina.configure(4, 4, 4, 7);
    check_true(!ina.overflow(),   "no overflow after configure");

    ina.shutdown();
    k_sleep(K_MSEC(5));
    ina.wake();
    k_sleep(K_MSEC(5));
    check_near(ina.voltage(), 0.0f, 40.0f, "voltage after wake");

    ina.set_alert(INA226Full::BOL, 50.0f);
    uint16_t flags = ina.alert_flags();
    check_true((flags & INA226Full::AFF) == 0, "no alert at 50V threshold");

    ina.reset();
    check_true(ina.manufacturer_id() == 0x5449, "manufacturer_id after reset");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
