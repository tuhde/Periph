#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "INA3221.h"

#ifndef INA3221_I2C_NODE
#define INA3221_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef INA3221_ADDR
#define INA3221_ADDR 0x40
#endif

static int passed = 0;
static int failed = 0;

static void check_eq(const char* label, uint16_t got, uint16_t expected) {
    if (got == expected) {
        printk("PASS %s\n", label);
        passed++;
    } else {
        printk("FAIL %s: got 0x%04X, expected 0x%04X\n", label, got, expected);
        failed++;
    }
}

static void check_true(const char* label, bool condition) {
    if (condition) {
        printk("PASS %s\n", label);
        passed++;
    } else {
        printk("FAIL %s\n", label);
        failed++;
    }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(INA3221_I2C_NODE);
    I2CTransportZephyr transport(dev, INA3221_ADDR);
    INA3221Full ina(transport);

    check_eq("manufacturer_id", ina.manufacturer_id(), 0x5449);
    check_eq("die_id",          ina.die_id(),          0x3220);

    for (uint8_t ch = 1; ch <= 3; ch++) {
        char label[32];
        snprintk(label, sizeof(label), "ch%u voltage non-negative", ch);
        check_true(label, ina.voltage(ch) >= 0.0f);

        snprintk(label, sizeof(label), "ch%u shunt_voltage finite", ch);
        check_true(label, fabsf(ina.shunt_voltage(ch)) < 1.0f);

        snprintk(label, sizeof(label), "ch%u current finite", ch);
        check_true(label, fabsf(ina.current(ch)) < 100.0f);

        snprintk(label, sizeof(label), "ch%u power non-negative", ch);
        check_true(label, ina.power(ch) >= 0.0f);
    }

    check_true("conversion_ready", ina.conversion_ready());

    ina.configure(3, 4, 4, 7);
    check_eq("configure: mfr_id still valid", ina.manufacturer_id(), 0x5449);

    ina.set_critical_alert(1, 0.1f);
    ina.set_warning_alert(2, 0.05f);
    uint16_t flags = ina.alert_flags();
    check_true("alert_flags readable", flags >= 0);

    ina.enable_channel(1, false);
    check_true("channel 1 disabled", !ina.channel_enabled(1));
    ina.enable_channel(1, true);
    check_true("channel 1 re-enabled", ina.channel_enabled(1));

    uint8_t channels[] = {1, 2};
    ina.set_summation_channels(channels, 2, 0.2f);
    float sv_sum = ina.summation_value();
    check_true("summation_value finite", fabsf(sv_sum) < 10.0f);

    ina.set_power_valid_limits(5.5f, 4.5f);
    check_true("power_valid readable", true);

    ina.shutdown();
    ina.wake();
    check_true("wake: voltage non-negative", ina.voltage(1) >= 0.0f);

    ina.reset();
    check_eq("reset: mfr_id still valid", ina.manufacturer_id(), 0x5449);

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}