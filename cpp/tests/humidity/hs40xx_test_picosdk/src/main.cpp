#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "HS40xx.h"

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
    I2CConnectionPicoSDK connection(i2c0, 0x54);
    HS40xxFull sensor(connection);

    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate

    float t = 0, rh = 0;
    uint16_t rh_raw = 0, t_raw = 0;
    uint8_t hb = 0, tb = 0, st = 0;
    uint32_t id = 0;

    CHECK(sensor.read(t, rh), "read ok");
    NEAR(t, -40.0f, 125.0f, "temperature range");
    NEAR(rh, 0.0f, 100.0f, "humidity range");
    NEAR(sensor.read_temperature(), -40.0f, 125.0f, "read_temperature range");
    NEAR(sensor.read_humidity(), 0.0f, 100.0f, "read_humidity range");
    CHECK(sensor.read_raw(rh_raw, t_raw), "read_raw ok");
    CHECK(rh_raw <= 16383 && t_raw <= 16383, "raw ranges");
    CHECK(sensor.read_id(id) && id != 0, "read_id");

    CHECK(sensor.set_resolution(12, 10), "set_resolution ok");
    CHECK(sensor.get_resolution(hb, tb) && hb == 12 && tb == 10, "resolution readback");
    CHECK(sensor.set_resolution(14, 14), "restore resolution");

    CHECK(sensor.start_periodic(0.5f), "start_periodic");
    TEST_DELAY_MS(1000);
    CHECK(sensor.read_periodic(t, rh, st), "read_periodic");
    NEAR(rh, 0.0f, 100.0f, "periodic humidity range");
    CHECK(sensor.stop_periodic(), "stop_periodic");
    CHECK(sensor.soft_reset(), "soft_reset");

    NEAR(sensor.dew_point(), -60.0f, 60.0f, "dew point plausible");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
