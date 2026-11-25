#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include <math.h>
#include "LC709203F.h"

int passed = 0;
int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static void check_near(float v, float lo, float hi, const char *label) {
    if (v >= lo && v <= hi) { printf("PASS %s\n", label); passed++; }
    else { printf("FAIL %s: %.4f not in [%.4f, %.4f]\n", label, (double)v, (double)lo, (double)hi); failed++; }
}

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x0B);
    LC709203FFull gauge(connection);

    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate
    check_true(gauge.voltage() >= 2.5f && gauge.voltage() <= 4.6f, "voltage range");
    check_true(gauge.valid(), "crc valid");
    check_true(gauge.percent() >= 0.0f && gauge.percent() <= 100.0f, "percent range");
    check_true(gauge.temperature() > 0.0f && gauge.temperature() < 60.0f, "temperature range");
    check_true(gauge.rsoc() <= 100, "rsoc range");
    check_true(gauge.profile_id() != 0, "profile_id");
    check_true(gauge.get_profile() == 1, "profile");
    check_true((gauge.set_temperature(30.0f), fabsf(gauge.temperature() - 30.0f) < 0.2f), "set_temperature");
    check_true((gauge.set_apa(0x19), gauge.get_apa() == 0x19), "apa");
    check_true((gauge.set_apt(0x1E), gauge.get_apt() == 0x1E), "apt");
    check_true((gauge.set_alarm_rsoc(20), gauge.get_alarm_rsoc() == 20), "alarm_rsoc");
    check_true((gauge.set_alarm_voltage(3.5f), fabsf(gauge.get_alarm_voltage() - 3.5f) < 0.002f), "alarm_voltage");
    check_true(gauge.pollInterrupt() <= 3, "pollInterrupt");
    check_true((gauge.init_rsoc(), gauge.rsoc() <= 100), "init_rsoc");
    gauge.sleep();
    sleep_ms(5);
    gauge.wake();
    check_true(!gauge.is_sleeping(), "wake: not sleeping");
    check_true(gauge.voltage() > 0.0f, "wake: voltage readable");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
