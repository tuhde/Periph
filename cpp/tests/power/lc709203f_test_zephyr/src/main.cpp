#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include <math.h>
#include "LC709203F.h"

#ifndef LC709203F_I2C_NODE
#define LC709203F_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef LC709203F_ADDR
#define LC709203F_ADDR 0x0B
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
    const struct device *i2c_dev = DEVICE_DT_GET(LC709203F_I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, LC709203F_ADDR);
    LC709203FFull gauge(connection);
    check_true(gauge.voltage() >= 2.5f && gauge.voltage() <= 4.6f, "voltage range");
    check_true(gauge.valid(), "crc valid");
    check_true(gauge.percent() >= 0.0f && gauge.percent() <= 100.0f, "percent range");
    check_true(gauge.temperature() > 0.0f && gauge.temperature() < 60.0f, "temperature range");
    check_true(gauge.rsoc() <= 100, "rsoc range");
    check_true(gauge.profile_id() != 0, "profile_id");
    check_true(gauge.get_profile() == 1, "profile");
    check_true((gauge.set_temperature(30.0f), fabsf(gauge.temperature() - 30.0f) < 0.2f), "set_temperature");
    check_true((gauge.set_apa(0x19), gauge.get_apa() == 0x19), "apa");
    check_true((gauge.set_apt(0x1E), gauge.get_apt() == 0x1E), "apt");
    check_true((gauge.set_alarm_rsoc(20), gauge.get_alarm_rsoc() == 20), "alarm_rsoc");
    check_true((gauge.set_alarm_voltage(3.5f), fabsf(gauge.get_alarm_voltage() - 3.5f) < 0.002f), "alarm_voltage");
    check_true(gauge.pollInterrupt() <= 3, "pollInterrupt");
    check_true((gauge.init_rsoc(), gauge.rsoc() <= 100), "init_rsoc");
    gauge.sleep();
    k_sleep(K_MSEC(5));
    gauge.wake();
    check_true(!gauge.is_sleeping(), "wake: not sleeping");
    check_true(gauge.voltage() > 0.0f, "wake: voltage readable");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
