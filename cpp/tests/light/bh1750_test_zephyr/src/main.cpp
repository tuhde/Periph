#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include <math.h>
#include "Bh1750.h"

#ifndef BH1750_I2C_NODE
#define BH1750_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef BH1750_ADDR
#define BH1750_ADDR 0x23
#endif

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}
#define CHECK(c, l) check_true((c), (l))
#define NEAR(v, lo, hi, l) check_true(((v) >= (lo) && (v) <= (hi)), (l))
#define TEST_DELAY_MS(ms) k_sleep(K_MSEC(ms))



int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(BH1750_I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, BH1750_ADDR);
    BH1750Full sensor(connection);

    sensor.init();                                          // Power on, () → void
    float lux = 0;
    uint16_t raw = 0;
    CHECK(sensor.read(lux), "read ok");                     // Measure illuminance, (lux&) → bool
    NEAR(lux, 0.0f, 120000.0f, "read_range");
    CHECK(sensor.read_raw(raw), "read_raw");
    CHECK(sensor.measure_once(BH1750Full::MODE_H, lux), "measure_once_h");
    CHECK(sensor.measure_once(BH1750Full::MODE_H2, lux), "measure_once_h2");
    CHECK(sensor.measure_once(BH1750Full::MODE_L, lux), "measure_once_l");
    CHECK(fabsf(sensor.lux_per_count() - 1.0f / 1.2f) < 1e-5f, "lux_per_count_default");
    CHECK(fabsf(sensor.measurement_time() - 0.180f) < 1e-5f, "measurement_time_default");
    CHECK(sensor.start_continuous(BH1750Full::MODE_H), "start_continuous");
    TEST_DELAY_MS(200);
    CHECK(sensor.read_continuous(lux), "read_continuous");
    NEAR(lux, 0.0f, 120000.0f, "read_continuous_range");
    CHECK(sensor.set_measurement_time(138), "set_measurement_time");
    CHECK(sensor.get_measurement_time_register() == 138, "mtreg_roundtrip");
    CHECK(fabsf(sensor.measurement_time() - 0.360f) < 1e-5f, "measurement_time_scaled");
    CHECK(sensor.measure_once(BH1750Full::MODE_H, lux), "read_with_mtreg");
    CHECK(sensor.set_sensitivity(1.0f), "set_sensitivity");
    CHECK(sensor.get_measurement_time_register() == 69, "sensitivity_default");
    sensor.power_down();
    sensor.power_on();
    sensor.reset();
    CHECK(sensor.measure_once(BH1750Full::MODE_H, lux), "read_after_reset");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
