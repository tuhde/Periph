#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "AM2315.h"

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
    I2CConnectionPicoSDK connection(i2c0, 0x5C);
    AM2315Full sensor(connection);

    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate

    float t = 0, rh = 0;
    uint16_t rh_raw = 0, t_raw = 0, model = 0, user1 = 0, user2 = 0;
    uint8_t version = 0, regs[7] = {0};
    uint32_t device_id = 0;

    CHECK(sensor.read_device_info(model, version, device_id), "read_device_info ok");
    CHECK(sensor.read(t, rh), "read ok");
    NEAR(t, -40.0f, 125.0f, "temperature range");
    NEAR(rh, 0.0f, 100.0f, "humidity range");
    NEAR(sensor.read_humidity(), 0.0f, 100.0f, "read_humidity range");
    NEAR(sensor.read_temperature(), -40.0f, 125.0f, "read_temperature range");
    CHECK(sensor.read_raw(rh_raw, t_raw), "read_raw ok");
    NEAR((float)rh_raw, 0.0f, 1000.0f, "raw humidity range");
    NEAR((float)(t_raw & 0x7FFF), 0.0f, 1250.0f, "raw temperature range");
    CHECK(sensor.read_fresh(t, rh), "read_fresh ok");
    NEAR(t, -40.0f, 125.0f, "fresh temperature range");
    CHECK(sensor.read_registers(0x00, 4, regs), "read_registers ok");

    CHECK(sensor.write_user_registers(0x1234, 0xABCD), "write_user_registers ok");
    CHECK(sensor.read_user_registers(user1, user2), "read_user_registers ok");
    CHECK(user1 == 0x1234 && user2 == 0xABCD, "user registers round trip");
    sensor.write_user_registers(0, 0);
    sensor.wake();

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
