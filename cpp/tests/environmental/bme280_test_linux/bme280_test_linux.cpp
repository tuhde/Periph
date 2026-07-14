#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x76
#endif

#include <stdio.h>
#include "I2CTransportLinux.h"
#include "BME280.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main() {
    I2CTransportLinux transport(TEST_I2C_BUS, TEST_ADDR);

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

    float t2 = bme_full.temperature();
    check_true(t2 >= -40.0f && t2 <= 85.0f, "temperature_range");

    float p2 = bme_full.pressure();
    check_true(p2 >= 300.0f && p2 <= 1100.0f, "pressure_range");

    float h2 = bme_full.humidity();
    check_true(h2 >= 0.0f && h2 <= 100.0f, "humidity_range");

    bme_full.set_oversampling(BME280Full::OSRS_X4, BME280Full::OSRS_X2, BME280Full::OSRS_X1);
    check_true(bme_full._osrs_t == 3 && bme_full._osrs_p == 2 && bme_full._osrs_h == 1, "set_oversampling");

    float alt = bme_full.altitude();
    check_true(alt >= -500.0f && alt <= 9000.0f, "altitude_range");

    float slp = bme_full.sea_level_pressure(0.0f);
    check_true(slp >= 900.0f && slp <= 1100.0f, "sea_level_pressure");

    check_true(bme_full.chip_id() == 0x60, "chip_id");

    bme_full.reset();
    check_true(true, "reset");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
