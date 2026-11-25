#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "HS30xx.h"

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
    I2CConnectionPicoSDK connection(i2c0, 0x44);
    HS30xxFull sensor(connection);

    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate

    float t = 0, rh = 0;
    uint16_t rh_raw = 0, t_raw = 0;
    uint8_t status = 9;

    CHECK(sensor.read(t, rh), "read ok");
    NEAR(t, -40.0f, 125.0f, "temperature range");
    NEAR(rh, 0.0f, 100.0f, "humidity range");
    NEAR(sensor.read_temperature(), -40.0f, 125.0f, "read_temperature range");
    NEAR(sensor.read_humidity(), 0.0f, 100.0f, "read_humidity range");
    CHECK(sensor.read_raw(rh_raw, t_raw, status), "read_raw ok");
    CHECK(rh_raw <= 16383 && t_raw <= 16383, "raw ranges");
    CHECK(status == 0, "raw status valid");

    sensor.set_measurement_wait(60);
    NEAR(sensor.read_humidity(), 0.0f, 100.0f, "read with 60 ms wait");
    sensor.set_measurement_wait(40);

    NEAR(sensor.dew_point(), -60.0f, 60.0f, "dew point plausible");

    // NVM access (resolution, ID) needs a power-cycle helper and is not exercised here.

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
