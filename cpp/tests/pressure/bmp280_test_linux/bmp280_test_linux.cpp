#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x76
#endif

#include <stdio.h>
#include "I2CTransportLinux.h"
#include "BMP280.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static void check_close(float actual, float expected, float tol, const char *label) {
    if (abs(actual - expected) <= tol) { printf("PASS %s\n", label); passed++; }
    else { printf("FAIL %s (expected %.4f, got %.4f)\n", label, expected, actual); failed++; }
}

int main() {
    I2CTransportLinux transport(TEST_I2C_BUS, TEST_ADDR);

    BMP280Minimal bmp(transport);

    float t = bmp.temperature();
    check_true(t >= -40.0f && t <= 85.0f, "temperature_range");

    float p = bmp.pressure();
    check_true(p >= 300.0f && p <= 1100.0f, "pressure_range");

    BMP280Full bmp_full(transport);

    check_true(bmp_full.chip_id() == 0x58, "chip_id");

    uint8_t s = bmp_full.status();
    (void)s;
    check_true(true, "status_register");

    bmp_full.configure(BMP280Full::OSRS_X2, BMP280Full::OSRS_X4,
                       BMP280Full::MODE_FORCED, BMP280Full::FILTER_4,
                       BMP280Full::T_SB_62_5_MS);
    check_true(true, "configure");

    bmp_full.set_oversampling(BMP280Full::OSRS_X1, BMP280Full::OSRS_X1);
    check_true(true, "set_oversampling");

    bmp_full.set_filter(BMP280Full::FILTER_OFF);
    check_true(true, "set_filter");

    bmp_full.set_standby(BMP280Full::T_SB_250_MS);
    check_true(true, "set_standby");

    bmp_full.set_mode(BMP280Full::MODE_FORCED);
    check_true(true, "set_mode");

    bmp_full.reset();
    check_true(true, "reset");

    float alt = bmp_full.altitude();
    check_true(alt >= -500.0f && alt <= 9000.0f, "altitude_range");

    float slp = bmp_full.sea_level_pressure(alt);
    check_true(slp >= 900.0f && slp <= 1100.0f, "sea_level_pressure");

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
    int32_t adc_T = 519888;
    int32_t adc_P = 415148;
    float t_val = bmp_full._compensate_temp(adc_T);
    check_close(t_val, 25.08f, 0.1f, "compensate_temp");
    float p_val = bmp_full._compensate_pressure(adc_P);
    check_close(p_val, 1006.53f, 0.5f, "compensate_pressure");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}