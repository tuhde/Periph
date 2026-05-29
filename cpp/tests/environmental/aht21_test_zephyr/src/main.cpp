#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "AHT21.h"

#ifndef AHT21_I2C_NODE
#define AHT21_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef AHT21_ADDR
#define AHT21_ADDR 0x38
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
    const struct device *i2c_dev = DEVICE_DT_GET(AHT21_I2C_NODE);
    I2CTransportZephyr transport(i2c_dev, AHT21_ADDR);
    AHT21Full aht(transport);

    check_true(aht.is_calibrated(), "is_calibrated");
    check_true(!aht.is_busy(), "not busy at idle");

    float t, h;
    aht.read(t, h);
    check_near(t, -40.0f, 120.0f, "temperature range");
    check_near(h, 0.0f, 100.0f, "humidity range");

    float tr = aht.temperature();
    check_near(tr, -40.0f, 120.0f, "read_temperature range");

    float hr = aht.humidity();
    check_near(hr, 0.0f, 100.0f, "read_humidity range");

    float tc, hc;
    bool crc_ok = aht.read_with_crc(tc, hc);
    check_true(crc_ok, "crc_ok");
    check_near(tc, -40.0f, 120.0f, "crc temperature range");
    check_near(hc, 0.0f, 100.0f, "crc humidity range");

    aht.soft_reset();
    k_sleep(K_MSEC(50));
    check_true(aht.is_calibrated(), "calibrated after reset");

    float t2, h2;
    aht.read(t2, h2);
    check_near(t2, -40.0f, 120.0f, "read after reset: temperature range");
    check_near(h2, 0.0f, 100.0f, "read after reset: humidity range");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
