#include <cstdio>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "HTU21DF.h"

#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x40
#endif

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) { printf("PASS %s\n", label); passed++; }
    else           { printf("FAIL %s\n", label); failed++; }
}
#define CHECK(c, l) check_true((l), (c))
#define NEAR(v, lo, hi, l) check_true((l), ((v) >= (lo) && (v) <= (hi)))
#define TEST_DELAY_MS(ms) usleep((ms) * 1000)

int main() {
    I2CConnectionLinux connection(TEST_I2C_BUS, TEST_ADDR);
    HTU21DFFull sensor(connection);

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
