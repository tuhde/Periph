#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include <math.h>
#include "Max17048.h"

#ifndef MAX17048_I2C_NODE
#define MAX17048_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef MAX17048_ADDR
#define MAX17048_ADDR 0x36
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
    const struct device *i2c_dev = DEVICE_DT_GET(MAX17048_I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, MAX17048_ADDR);
    Max17048Full gauge(connection);
    check_true(gauge.voltage() >= 2.5f && gauge.voltage() <= 4.6f, "voltage range");
    check_true(gauge.percent() >= 0.0f && gauge.percent() <= 100.0f, "percent range");
    check_true((gauge.version() & 0xFFF0) == 0x0010, "version");
    check_true(fabsf((gauge.raw_voltage() >> 4) * 1.25e-3f - gauge.voltage()) < 0.05f, "raw_voltage matches voltage");
    check_true(fabsf(gauge.charge_rate()) < 1000.0f, "charge_rate readable");
    check_true((gauge.set_rcomp(0x97), gauge.get_rcomp() == 0x97), "rcomp");
    check_true((gauge.compensate_temperature(20.0f), gauge.get_rcomp() == 0x97), "compensate 20 C");
    check_true((gauge.set_alert_soc(10), gauge.get_alert_soc() == 10), "alert_soc");
    gauge.set_alert_soc(4);
    float lo = 0, hi = 0;
    gauge.set_voltage_alert(3.0f, 4.2f);
    gauge.get_voltage_alert(&lo, &hi);
    check_true(fabsf(lo - 3.0f) < 0.001f && fabsf(hi - 4.2f) < 0.001f, "voltage_alert");
    gauge.set_voltage_alert(0.0f, 5.1f);
    check_true((gauge.set_reset_voltage(2.8f), fabsf(gauge.get_reset_voltage() - 2.8f) < 0.001f), "reset_voltage");
    gauge.set_reset_voltage(3.0f);
    float hib = 0, act = 0;
    gauge.set_hibernate_thresholds(26.624f, 0.06f);
    gauge.get_hibernate_thresholds(&hib, &act);
    check_true(fabsf(hib - 26.624f) < 0.001f && fabsf(act - 0.06f) < 0.001f, "hibernate_thresholds");
    gauge.clear_status(0x3F00);
    check_true((gauge.read_status() & 0x3F00) == 0, "status flags cleared");
    check_true(gauge.pollInterrupt() == 0, "pollInterrupt");
    check_true((gauge.write_config(gauge.read_config()), (gauge.read_config() >> 8) == 0x97), "config");
    gauge.enable_sleep(true);
    gauge.sleep();
    k_sleep(K_MSEC(5));
    gauge.wake();
    gauge.enable_sleep(false);
    check_true(gauge.voltage() > 0.0f, "wake: voltage readable");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
