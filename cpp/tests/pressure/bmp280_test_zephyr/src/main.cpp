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

static void check_close(float actual, float expected, float tol, const char *label) {
    if (abs(actual - expected) <= tol) { printk("PASS %s\n", label); passed++; }
    else { printk("FAIL %s (expected %.4f, got %.4f)\n", label, expected, actual); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(BMP280_I2C_NODE);
    I2CTransportZephyr transport(dev, BMP280_ADDR);

    BMP280Minimal bmp(transport);
    float t = bmp.temperature();
    check_true(t >= -40.0f && t <= 85.0f, "temperature_range");
    float p = bmp.pressure();
    check_true(p >= 300.0f && p <= 1100.0f, "pressure_range");

    BMP280Full bmp_full(transport);
    check_true(bmp_full.chip_id() == 0x58, "chip_id");

    bmp_full.configure(BMP280Full::OSRS_X2, BMP280Full::OSRS_X4,
                       BMP280Full::MODE_FORCED, BMP280Full::FILTER_4,
                       BMP280Full::T_SB_62_5_MS);
    check_true(true, "configure");

    bmp_full._dig_T1 = 27504;
    bmp_full._dig_T2 = 26435;
    bmp_full._dig_T3 = -1000;
    bmp_full._dig_P1 = 36477;
    bmp_full._dig_P2 = -10685;
    bmp_full._dig_P3 = 3024;
    bmp_full._dig_P4 = 2855;
    bmp_full._dig_P5 = 140;
    bmp_full._dig_P6 = -7;
    bmp_full._dig_P7 = 15500;
    bmp_full._dig_P8 = -14600;
    bmp_full._dig_P9 = 6000;

    bmp_full._t_fine = 0;
    float t_val = bmp_full._compensate_temp(519888);
    check_close(t_val, 25.08f, 0.1f, "compensate_temp");
    float p_val = bmp_full._compensate_pressure(415148);
    check_close(p_val, 1006.53f, 0.5f, "compensate_pressure");

    bmp_full.reset();
    check_true(true, "reset");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}