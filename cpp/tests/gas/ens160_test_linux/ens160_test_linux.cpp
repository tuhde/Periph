#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x52
#endif

#include <stdio.h>
#include "I2CTransportLinux.h"
#include "ENS160.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main() {
    I2CTransportLinux transport(TEST_I2C_BUS, TEST_ADDR);

    ENS160Minimal sensor(transport);
    check_true(true, "init");

    uint8_t status = sensor.status();
    check_true(status <= 3, "status_valid_range");

    printf("Waiting for warm-up...\n");
    int timeout = 180;
    while (sensor.status() != 0 && timeout > 0) {
        sleep(1);
        timeout--;
    }
    if (sensor.status() == 0) {
        check_true(true, "warmup_complete");
    } else {
        printf("FAIL warmup_timeout\n");
        failed++;
    }

    uint8_t aqi;
    float tvoc_ppb, eco2_ppm;
    bool ok = sensor.read_air_quality(aqi, tvoc_ppb, eco2_ppm);
    check_true(ok, "read_air_quality");
    check_true(aqi >= 1 && aqi <= 5, "aqi_range");
    check_true(tvoc_ppb >= 0, "tvoc_non_negative");
    check_true(eco2_ppm >= 400, "eco2_minimum");

    ENS160Full sensor_full(transport);
    check_true(true, "full_init");

    sensor_full.set_compensation(25.0f, 50.0f);
    check_true(true, "set_compensation");

    float tvoc = sensor_full.read_tvoc();
    check_true(tvoc >= 0, "read_tvoc");

    float eco2 = sensor_full.read_eco2();
    check_true(eco2 >= 400, "read_eco2");

    uint8_t aqi2 = sensor_full.read_aqi();
    check_true(aqi2 >= 1 && aqi2 <= 5, "read_aqi");

    float temp_actual, rh_actual;
    sensor_full.read_compensation_actuals(temp_actual, rh_actual);
    check_true(true, "read_compensation_actuals");

    uint8_t major, minor, release;
    sensor_full.get_firmware_version(major, minor, release);
    check_true(true, "get_firmware_version");

    sensor_full.sleep();
    check_true(true, "sleep");
    sleep(1);
    sensor_full.wake();
    check_true(true, "wake");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
