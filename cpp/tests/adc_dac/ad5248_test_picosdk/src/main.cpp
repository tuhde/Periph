#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "AD5248.h"

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
    I2CConnectionPicoSDK connection(i2c0, 0x2C);
    AD5248Full pot(connection);

    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate
    pot.reset();
    pot.set_wiper(0, 100);
    check_true(pot.get_wiper(0) == 100, "set/get wiper ch0");
    pot.set_wiper(1, 200);
    check_true(pot.get_wiper(1) == 200, "set/get wiper ch1");
    check_true(pot.get_wiper(0) == 100, "ch0 unchanged by ch1 write");
    pot.set_resistance(0, 5320.0f);
    check_true(pot.get_wiper(0) == 128, "set_resistance midscale code");
    check_true(pot.get_resistance(0) > 5319.0f && pot.get_resistance(0) < 5321.0f, "get_resistance ~5320");
    pot.set_both(10, 20);
    check_true(pot.get_wiper(0) == 10, "set_both ch0");
    check_true(pot.get_wiper(1) == 20, "set_both ch1");
    pot.set_ratio(0, 0.5f);
    check_true(pot.get_ratio(0) > 0.49f && pot.get_ratio(0) < 0.51f, "get_ratio ~0.5");
    pot.set_wiper(0, 250);
    check_true(pot.increment(0, 10) == 255, "increment clamps");
    pot.set_wiper(1, 77);
    pot.shutdown(1);
    check_true(pot.is_shutdown(1), "is_shutdown");
    pot.resume(1);
    check_true(pot.get_wiper(1) == 77, "RDAC kept across shutdown");
    pot.reset();
    check_true(pot.get_wiper(0) == 0x80, "reset ch0");
    check_true(pot.get_wiper(1) == 0x80, "reset ch1");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
