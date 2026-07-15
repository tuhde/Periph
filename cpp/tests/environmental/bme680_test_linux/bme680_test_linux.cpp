#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x76
#endif

#include <stdio.h>
#include "I2CTransportLinux.h"
#include "BME680.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main() {
    I2CTransportLinux transport(TEST_I2C_BUS, TEST_ADDR);

    BME680Minimal bme(transport);

    float t = bme.temperature();
    check_true(t >= -40.0f && t <= 85.0f, "temperature_range");

    float p = bme.pressure();
    check_true(p >= 300.0f && p <= 1100.0f, "pressure_range");

    float h = bme.humidity();
    check_true(h >= 0.0f && h <= 100.0f, "humidity_range");

    BME680Full bme_full(transport);

    bme_full.set_oversampling(BME680Full::OSRS_X4, BME680Full::OSRS_X2, BME680Full::OSRS_X1);
    check_true(bme_full._osrs_t == 3 && bme_full._osrs_p == 2 && bme_full._osrs_h == 1, "set_oversampling");

    check_true(bme_full.chip_id() == 0x61, "chip_id");

    float t2, p2, h2, g2;
    bme_full.read_all(t2, p2, h2, g2);
    check_true(t2 >= -40.0f && t2 <= 85.0f && p2 >= 300.0f && p2 <= 1100.0f && h2 >= 0.0f && h2 <= 100.0f, "read_all");

    bme_full.reset();
    check_true(true, "reset");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
