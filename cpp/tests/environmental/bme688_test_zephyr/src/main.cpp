#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "BME688.h"

#ifndef BME688_I2C_NODE
#define BME688_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef BME688_ADDR
#define BME688_ADDR 0x76
#endif

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(BME688_I2C_NODE);
    I2CConnectionZephyr connection(dev, BME688_ADDR);
    BME688Minimal bme(connection);

    float t = bme.temperature();
    check_true(t >= -40.0f && t <= 85.0f, "temperature_range");

    float p = bme.pressure();
    check_true(p >= 300.0f && p <= 1100.0f, "pressure_range");

    float h = bme.humidity();
    check_true(h >= 0.0f && h <= 100.0f, "humidity_range");

    BME688Full bme_full(connection);
    bme_full.set_oversampling(BME688Full::OSRS_X4, BME688Full::OSRS_X2, BME688Full::OSRS_X1);
    check_true(bme_full._osrs_t == 3 && bme_full._osrs_p == 2 && bme_full._osrs_h == 1, "set_oversampling");

    check_true(bme_full.chip_id() == 0x61, "chip_id");
    check_true(bme_full.variant_id() == 0x01, "variant_id");

    {
        const int16_t ptemps[2] = {320, 250};
        const uint8_t prep[2] = {1, 1};
        bme_full.start_parallel(ptemps, prep, 2, 100.0f);
        k_msleep(2000);
        BME688Field pf[3];
        uint8_t pn = bme_full.read_parallel(pf, 3);
        bme_full.stop_parallel();
        check_true(pn > 0 && pf[0].temperature >= -40.0f && pf[0].temperature <= 85.0f, "parallel_mode");
    }

    float t2, p2, h2, g2;
    bme_full.read_all(t2, p2, h2, g2);
    check_true(t2 >= -40.0f && t2 <= 85.0f && p2 >= 300.0f && p2 <= 1100.0f && h2 >= 0.0f && h2 <= 100.0f, "read_all");

    bme_full.reset();
    check_true(true, "reset");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
