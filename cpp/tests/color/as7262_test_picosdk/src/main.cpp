#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "As7262.h"

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
    I2CConnectionPicoSDK connection(i2c0, 0x49);
    AS7262Full sensor(connection);

    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate

    AS7262Channels ch = {};
    AS7262RawChannels raw = {};
    uint8_t u8a = 0, u8b = 0;
    uint16_t u16 = 0;

    CHECK(sensor.begin(), "begin");
    CHECK(sensor.device_type() == 0x40, "device type");
    CHECK(sensor.hardware_version(u8a, u8b) && u8a == 0x40 && u8b == 0x3E, "hardware version");
    NEAR(sensor.temperature(), -40.0f, 125.0f, "temperature range");

    CHECK(sensor.read(ch), "read ok");
    CHECK(ch.violet >= 0.0f && ch.blue >= 0.0f && ch.green >= 0.0f && ch.yellow >= 0.0f && ch.orange >= 0.0f && ch.red >= 0.0f, "calibrated channels");
    CHECK(sensor.read_raw(raw), "read_raw ok");
    CHECK(sensor.read_channel_raw(2, u16), "read_channel_raw");

    CHECK(sensor.set_gain(64), "set_gain 64");
    CHECK(sensor.get_gain() == 64.0f, "gain readback");
    CHECK(sensor.set_gain(16), "set_gain 16");
    CHECK(sensor.set_integration_time(50.0f), "set_integration_time");
    NEAR(sensor.get_integration_time(), 50.3f, 50.5f, "integration time readback");
    CHECK(sensor.set_mode(2), "set_mode 2");
    CHECK(sensor.get_mode() == 2, "mode readback");
    CHECK(sensor.set_mode(3), "set_mode 3");

    CHECK(sensor.measure(), "measure");
    TEST_DELAY_MS(600);
    CHECK(sensor.data_ready(), "data ready");
    CHECK(sensor.read_calibrated(ch), "read_calibrated");

    CHECK(sensor.set_indicator_led(true, 4.0f), "indicator led on");
    CHECK(sensor.set_indicator_led(false), "indicator led off");
    CHECK(sensor.soft_reset(), "soft_reset");
    NEAR(sensor.get_integration_time(), 700.0f, 714.0f, "reset int_t");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
