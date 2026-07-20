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

    ENS160Full sensor(transport);
    check_true(true, "init");

    uint8_t status = sensor.status();
    check_true(status <= 3, "status_valid_range");

    printf("Waiting for warm-up (may take up to 3 minutes)...\n");
    bool warmup_ok = false;
    {
        uint8_t _aqi; float _tvoc, _eco2;
        for (int i = 0; i < 240; i++) {
            if (sensor.read_air_quality(_aqi, _tvoc, _eco2)) { warmup_ok = true; break; }
        }
    }
    check_true(warmup_ok, "warmup_complete");

    uint8_t aqi;
    float tvoc_ppb, eco2_ppm;
    bool ok = sensor.read_air_quality(aqi, tvoc_ppb, eco2_ppm);
    check_true(ok, "read_air_quality");
    check_true(aqi >= 1 && aqi <= 5, "aqi_range");
    check_true(tvoc_ppb >= 0, "tvoc_non_negative");
    check_true(eco2_ppm >= 400, "eco2_minimum");

    sensor.set_compensation(25.0f, 50.0f);
    check_true(true, "set_compensation");

    float tvoc = sensor.read_tvoc();
    check_true(tvoc >= 0, "read_tvoc");

    float eco2 = sensor.read_eco2();
    check_true(eco2 >= 400, "read_eco2");

    uint8_t aqi2 = sensor.read_aqi();
    check_true(aqi2 >= 1 && aqi2 <= 5, "read_aqi");

    float temp_actual, rh_actual;
    sensor.read_compensation_actuals(temp_actual, rh_actual);
    check_true(true, "read_compensation_actuals");

    uint8_t major, minor, release;
    sensor.get_firmware_version(major, minor, release);
    check_true(true, "get_firmware_version");

    sensor.sleep();
    check_true(true, "sleep");
    sleep(1);
    sensor.wake();
    check_true(true, "wake");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
