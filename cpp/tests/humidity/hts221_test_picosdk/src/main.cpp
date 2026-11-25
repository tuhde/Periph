#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "Hts221.h"

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}
#define CHECK(c, l) check_true((c), (l))
#define NEAR(v, lo, hi, l) check_true(((v) >= (lo) && (v) <= (hi)), (l))
#define TEST_DELAY_MS(ms) sleep_ms(ms)



int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x5F);
    HTS221Full sensor(connection);

    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate


    CHECK(sensor.init(), "init");
    CHECK(sensor.who_am_i() == 0xBC, "who_am_i");
    HTS221Calibration cal = {};
    sensor.read_calibration(cal);
    CHECK(cal.h1_rh > cal.h0_rh && cal.t1_degc > cal.t0_degc, "calibration_plausible");
    float t = 0, rh = 0, hz = 0;
    CHECK(sensor.read(t, rh), "read ok");
    NEAR(t, -40.0f, 85.0f, "read_temperature_range");
    NEAR(rh, 0.0f, 100.0f, "read_humidity_range");
    NEAR(sensor.read_humidity(), 0.0f, 100.0f, "read_humidity_single");
    float tc = sensor.read_temperature();
    NEAR(tc, -40.0f, 85.0f, "read_temperature_single");
    CHECK(sensor.dew_point() <= tc + 1.0f, "dew_point_below_temperature");
    uint16_t ts = 0, hs = 0;
    CHECK(sensor.set_averaging(32, 64), "set_averaging");
    CHECK(sensor.get_averaging(ts, hs) && ts == 32 && hs == 64, "averaging_roundtrip");
    CHECK(sensor.set_averaging(16, 32), "set_averaging default");
    CHECK(sensor.set_output_data_rate(1), "set_output_data_rate");
    CHECK(sensor.get_output_data_rate(hz) && hz == 1.0f, "odr_roundtrip");
    TEST_DELAY_MS(1500);
    float ct = 0, crh = 0;
    CHECK(sensor.read_continuous(ct, crh), "read_continuous");
    NEAR(crh, 0.0f, 100.0f, "continuous_humidity_range");
    CHECK(sensor.set_output_data_rate(0), "set_output_data_rate one-shot");
    CHECK(sensor.set_heater(true) && sensor.is_heater_on(), "heater_on");
    CHECK(sensor.set_heater(false) && !sensor.is_heater_on(), "heater_off");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
