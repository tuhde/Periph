#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "LTC4151.h"

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
    I2CConnectionPicoSDK connection(i2c0, 0x6A);
    LTC4151Full ltc(connection);

    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate
    check_near(ltc.voltage(), 7.0f, 80.0f, "voltage range");
    check_near(ltc.current(), 0.0f, 4.096f, "current range");
    check_near(ltc.power(), 0.0f, 330.0f, "power range");
    check_near(ltc.sense_voltage(), 0.0f, 0.08192f, "sense voltage range");
    check_near(ltc.adin_voltage(), 0.0f, 2.048f, "adin range");
    LTC4151Full::Readings r;
    ltc.read_all(r);
    check_near(r.vin, 7.0f, 80.0f, "read_all vin");
    check_true(ltc.read_raw(LTC4151Full::VIN) > 0 && ltc.read_raw(LTC4151Full::VIN) < 4096, "read_raw vin");
    check_true(ltc.read_control() == 0x0C, "control default");
    check_true(!ltc.is_snapshot_mode(), "not snapshot mode");
    float s = 0;
    check_true(ltc.snapshot(LTC4151Full::VIN, s) && s >= 7.0f && s <= 80.0f, "snapshot vin");
    check_true(!ltc.is_snapshot_mode(), "back in continuous");
    ltc.set_stuck_bus_timer(false);
    check_true(ltc.read_control() == 0x08, "stuck-bus timer off");
    ltc.set_stuck_bus_timer(true);
    check_true(ltc.read_control() == 0x0C, "stuck-bus timer on");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
