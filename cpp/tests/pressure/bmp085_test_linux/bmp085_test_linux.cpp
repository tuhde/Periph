#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x77
#endif

#include <stdio.h>
#include "I2CConnectionLinux.h"
#include "BMP085.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static void check_near(float val, float lo, float hi, const char *label) {
    if (val >= lo && val <= hi) { printf("PASS %s\n", label); passed++; }
    else { printf("FAIL %s: %.4f not in [%.4f, %.4f]\n", label, (double)val, (double)lo, (double)hi); failed++; }
}

int main() {
    I2CConnectionLinux connection(TEST_I2C_BUS, TEST_ADDR);

    BMP085Minimal bmp(connection);

    float t = bmp.temperature();
    check_near(t, -40.0f, 85.0f, "temperature_range");

    float p = bmp.pressure();
    check_near(p, 30000.0f, 110000.0f, "pressure_range");

    BMP085Full bmp_full(connection, BMP085Full::OSS_ULP);
    check_true(bmp_full.oversampling() == BMP085Full::OSS_ULP, "default_oss");
    bmp_full.set_oversampling(BMP085Full::OSS_HIGH_RES);
    check_true(bmp_full.oversampling() == BMP085Full::OSS_HIGH_RES, "set_oss");

    float alt = bmp_full.altitude();
    check_true(alt >= -500.0f && alt <= 9000.0f, "altitude_range");

    float slp = bmp_full.sea_level_pressure(0.0f);
    check_near(slp, 90000.0f, 110000.0f, "sea_level_pressure");

    check_true(bmp_full.chip_id() == 0x55, "chip_id");

    bmp_full.reset();
    check_true(true, "reset");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}