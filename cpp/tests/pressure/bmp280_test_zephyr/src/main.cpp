#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "BMP280.h"

#ifndef BMP280_I2C_NODE
#define BMP280_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef BMP280_ADDR
#define BMP280_ADDR 0x76
#endif

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(BMP280_I2C_NODE);
    I2CTransportZephyr transport(dev, BMP280_ADDR);
    BMP280Minimal bmp(transport);

    bmp._dig_T1 = 27504;
    bmp._dig_T2 = 26435;
    bmp._dig_T3 = -1000;
    bmp._dig_P1 = 36477;
    bmp._dig_P2 = -10685;
    bmp._dig_P3 = 3024;
    bmp._dig_P4 = 2855;
    bmp._dig_P5 = 140;
    bmp._dig_P6 = -7;
    bmp._dig_P7 = 15500;
    bmp._dig_P8 = -14600;
    bmp._dig_P9 = 6000;

    float t = bmp._compensate_temp(519888);
    check_true(t > 24.0f && t < 26.0f, "temp_compensation");

    bmp._compensate_temp(519888);
    float p = bmp._compensate_pressure(415148);
    check_true(p > 1005.0f && p < 1008.0f, "pressure_compensation");

    BMP280Full bmp_full(transport);
    bmp_full.set_oversampling(BMP280Full::OSRS_X4, BMP280Full::OSRS_X2);
    check_true(bmp_full._osrs_t == 3 && bmp_full._osrs_p == 2, "set_oversampling");

    float alt = bmp_full.altitude();
    check_true(alt >= -500.0f && alt <= 9000.0f, "altitude_range");

    float slp = bmp_full.sea_level_pressure(0.0f);
    check_true(slp >= 900.0f && slp <= 1100.0f, "sea_level_pressure");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
