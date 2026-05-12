#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x77
#endif

#include <stdio.h>
#include "../../src/transport/I2CTransportLinux.h"
#include "../../src/chips/pressure/BMP180.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main() {
    I2CTransportLinux transport(TEST_I2C_BUS, TEST_ADDR);
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

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
