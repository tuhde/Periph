#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "BMP180.h"

#ifndef BMP180_I2C_NODE
#define BMP180_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef BMP180_ADDR
#define BMP180_ADDR 0x77
#endif

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(BMP180_I2C_NODE);
    I2CTransportZephyr transport(dev, BMP180_ADDR);
    BMP180Minimal bmp(transport);

    bmp._oss = 0;
    bmp._b5 = 0;
    bmp._ac1 = 408;
    bmp._ac2 = -72;
    bmp._ac3 = -14383;
    bmp._ac4 = 32741;
    bmp._ac5 = 32757;
    bmp._ac6 = 23153;
    bmp._b1 = 6190;
    bmp._b2 = 4;
    bmp._mc = -8711;
    bmp._md = 2868;

    int32_t b5 = bmp._compensate_temp(27898);
    check_true(b5 != 0, "temp_compensation_b5");

    BMP180Full bmp_full(transport, 0);
    check_true(bmp_full.oversampling() == 0, "default_oss");
    bmp_full.set_oversampling(2);
    check_true(bmp_full.oversampling() == 2, "set_oss");

    float alt = bmp_full.altitude();
    check_true(alt >= 0.0f, "altitude");
    float slp = bmp_full.sea_level_pressure(0.0f);
    check_true(slp >= 900.0f && slp <= 1100.0f, "sea_level_pressure");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
