#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "SHT31.h"

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
    SHT31Full sensor(connection);

    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate

    float t = 0, rh = 0;
    CHECK(sensor.read(t, rh), "read ok");
    NEAR(t, -40.0f, 125.0f, "temperature range");
    NEAR(rh, 0.0f, 100.0f, "humidity range");
    NEAR(sensor.read_temperature(), -40.0f, 125.0f, "read_temperature range");
    NEAR(sensor.read_humidity(), 0.0f, 100.0f, "read_humidity range");
    uint16_t raw_t = 0, raw_rh = 0;
    CHECK(sensor.read_raw(raw_t, raw_rh), "read_raw ok");

    for (uint8_t level = 0; level < 3; level++) {
        sensor.set_repeatability(level);
        CHECK(sensor.read(t, rh), "repeatability read");
    }
    sensor.set_repeatability(SHT31Full::REPEATABILITY_HIGH);

    uint16_t status = 0;
    CHECK(sensor.read_status(status), "status readable");
    sensor.clear_status();
    CHECK(sensor.read_status(status) && !(status & 0x0010), "status bit 4 cleared");

    CHECK(sensor.start_periodic(2.0f, SHT31Full::REPEATABILITY_HIGH), "start_periodic");
    TEST_DELAY_MS(1200);
    CHECK(sensor.fetch(t, rh), "periodic fetch");
    sensor.stop_periodic();

    sensor.set_heater(true);
    CHECK(sensor.read_status(status) && (status & 0x2000), "heater bit set");
    sensor.set_heater(false);
    CHECK(sensor.read_status(status) && !(status & 0x2000), "heater bit cleared");

    NEAR(sensor.dew_point(), -60.0f, 60.0f, "dew point plausible");
    sensor.soft_reset();
    CHECK(sensor.read(t, rh), "read after soft reset");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
