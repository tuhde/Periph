#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include <math.h>
#include "Shtc3.h"

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
    I2CConnectionPicoSDK connection(i2c0, 0x70);
    SHTC3Full sensor(connection);

    CHECK(sensor.init(), "init ok");                        // Soft reset and ID check, () → bool
    float t = 0, rh = 0;
    uint16_t tr = 0, rr = 0, id = 0;
    CHECK(sensor.read(t, rh), "read ok");                   // Read temperature and humidity, (temperature_c&, humidity_pct&) → bool
    NEAR(t, -45.0f, 130.0f, "read_temperature_range");
    NEAR(rh, 0.0f, 100.0f, "read_humidity_range");
    NEAR(sensor.read_humidity(), 0.0f, 100.0f, "read_humidity_single");
    float tc = sensor.read_temperature();
    NEAR(tc, -45.0f, 130.0f, "read_temperature_single");
    CHECK(sensor.read_raw(tr, rr), "read_raw");
    CHECK(sensor.dew_point() <= tc + 1.0f, "dew_point_below_temperature");
    CHECK(sensor.read_id(id) && (id & 0x083F) == 0x0807, "read_id_product_code");
    sensor.set_low_power(true);
    CHECK(sensor.read(t, rh), "low_power_read");
    sensor.set_low_power(false);
    sensor.set_sleep_between(false);
    CHECK(sensor.read(t, rh), "read_without_sleep");
    CHECK(sensor.read(t, rh), "read_without_sleep_again");
    sensor.sleep();
    sensor.wake();
    sensor.set_sleep_between(true);
    sensor.set_clock_stretching(true);
    CHECK(sensor.read(t, rh), "read_with_clock_stretching");
    sensor.set_clock_stretching(false);
    sensor.soft_reset();
    CHECK(sensor.read(t, rh), "read_after_reset");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
