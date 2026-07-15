#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "BME280.h"

#ifndef BME280_I2C_NODE
#define BME280_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef BME280_ADDR
#define BME280_ADDR 0x76
#endif

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(BME280_I2C_NODE);
    I2CTransportZephyr transport(dev, BME280_ADDR);
    BME280Minimal bme(transport);

    bme._dig_T1 = 27504;
    bme._dig_T2 = 26435;
    bme._dig_T3 = -1000;
    bme._dig_P1 = 36477;
    bme._dig_P2 = -10685;
    bme._dig_P3 = 3024;
    bme._dig_P4 = 2855;
    bme._dig_P5 = 140;
    bme._dig_P6 = -7;
    bme._dig_P7 = 15500;
    bme._dig_P8 = -14600;
    bme._dig_P9 = 6000;

    float t = bme._compensate_temp(519888);
    check_true(t > 24.0f && t < 26.0f, "temp_compensation");

    float p = bme._compensate_pressure(415148);
    check_true(p > 1005.0f && p < 1008.0f, "pressure_compensation");

    bme._dig_H1 = 75;
    bme._dig_H2 = 362;
    bme._dig_H3 = 0;
    bme._dig_H4 = 341;
    bme._dig_H5 = 50;
    bme._dig_H6 = 30;
    float h = bme._compensate_humidity(29000);
    check_true(h > 30.0f && h < 70.0f, "humidity_compensation");

    BME280Full bme_full(transport);
    bme_full.set_oversampling(BME280Full::OSRS_X4, BME280Full::OSRS_X2, BME280Full::OSRS_X1);
    check_true(bme_full._osrs_t == 3 && bme_full._osrs_p == 2 && bme_full._osrs_h == 1, "set_oversampling");

    float alt = bme_full.altitude();
    check_true(alt >= -500.0f && alt <= 9000.0f, "altitude_range");

    float slp = bme_full.sea_level_pressure(0.0f);
    check_true(slp >= 900.0f && slp <= 1100.0f, "sea_level_pressure");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
