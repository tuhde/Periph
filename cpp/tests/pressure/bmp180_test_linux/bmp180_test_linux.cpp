#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x77
#endif

#include <stdio.h>
#include "I2CTransportLinux.h"
#include "BMP180.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main() {
    I2CTransportLinux transport(TEST_I2C_BUS, TEST_ADDR);

    BMP180Minimal bmp(transport);

    float t = bmp.temperature();
    check_true(t >= -40.0f && t <= 85.0f, "temperature_range");

    float p = bmp.pressure();
    check_true(p >= 300.0f && p <= 1100.0f, "pressure_range");

    BMP180Full bmp_full(transport, BMP180Full::OSS_ULP);
    check_true(bmp_full.oversampling() == BMP180Full::OSS_ULP, "default_oss");
    bmp_full.set_oversampling(BMP180Full::OSS_HIGH_RES);
    check_true(bmp_full.oversampling() == BMP180Full::OSS_HIGH_RES, "set_oss");

    float alt = bmp_full.altitude();
    check_true(alt >= -500.0f && alt <= 9000.0f, "altitude_range");

    float slp = bmp_full.sea_level_pressure(0.0f);
    check_true(slp >= 900.0f && slp <= 1100.0f, "sea_level_pressure");

    check_true(bmp_full.chip_id() == 0x55, "chip_id");

    bmp_full.reset();
    check_true(true, "reset");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
