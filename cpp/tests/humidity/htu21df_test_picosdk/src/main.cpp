#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "HTU21DF.h"

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
    I2CConnectionPicoSDK connection(i2c0, 0x40);
    HTU21DFFull sensor(connection);

    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate

    float t = 0, rh = 0;
    sensor.init();
    CHECK((sensor.read_user_register() & 0x83) == 0x02, "user register after reset");

    CHECK(sensor.read(t, rh), "read ok");
    NEAR(t, -40.0f, 125.0f, "temperature range");
    NEAR(rh, -6.0f, 119.0f, "humidity range");
    NEAR(sensor.read_humidity(), -6.0f, 119.0f, "read_humidity range");
    NEAR(sensor.read_temperature(), -40.0f, 125.0f, "read_temperature range");
    NEAR(sensor.read_humidity_compensated(), -6.0f, 125.0f, "compensated humidity range");
    uint16_t raw_t = 0, raw_rh = 0;
    CHECK(sensor.read_raw(raw_t, raw_rh), "read_raw ok");

    for (uint8_t res = 0; res < 4; res++) {
        CHECK(sensor.set_resolution(res), "set_resolution");
        CHECK(sensor.get_resolution() == res, "get_resolution");
        CHECK(sensor.read(t, rh), "read at resolution");
    }
    sensor.set_resolution(0);

    sensor.set_heater(true);
    CHECK(sensor.heater(), "heater on");
    sensor.set_heater(false);
    CHECK(!sensor.heater(), "heater off");
    CHECK(!sensor.battery_low(), "battery ok");
    NEAR(sensor.dew_point(), -60.0f, 60.0f, "dew point plausible");
    sensor.soft_reset();
    CHECK(sensor.read(t, rh), "read after soft reset");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
