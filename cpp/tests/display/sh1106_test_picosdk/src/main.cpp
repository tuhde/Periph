#include <stdio.h>
#include <hardware/i2c.h>
#include <hardware/gpio.h>
#include <pico/stdlib.h>
#include "I2CConnectionPicoSDK.h"
#include "SH1106.h"
#include "sh1106_smoke.h"

static int passed = 0;
static int failed = 0;

static void report(const char* label, bool ok) {
    if (ok) { printf("PASS %s\n", label); passed++; }
    else    { printf("FAIL %s\n", label); failed++; }
}

int main(void) {
    stdio_init_all();
    sleep_ms(2000);

    i2c_init(i2c0, 400 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);

    I2CConnectionPicoSDK conn(i2c0, 0x3C);
    static SH1106Full chip(conn);
    sh1106Checks(chip, report);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    while (true) sleep_ms(1000);
}
