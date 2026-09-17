#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "ADE7953.h"

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool cond) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else      { printf("FAIL %s\n", label); failed++; }
}

int main(void) {
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);

    I2CConnectionPicoSDK connection(i2c0, 0x38);
    ADE7953Full ade(connection, 251.0f, 30.0f);

    stdio_init_all();
    sleep_ms(2000);

    check_true("voltage non-negative", ade.voltage() >= 0.0f);
    check_true("current non-negative", ade.current() >= 0.0f);
    check_true("activePower finite",   ade.activePower() > -1.0e6f);
    check_true("activeEnergy finite",  ade.activeEnergy() > -1000.0f);
    check_true("linePeriod positive",  ade.linePeriod() > 0.0f);

    ade.reset();
    check_true("voltage after reset", ade.voltage() >= 0.0f);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}