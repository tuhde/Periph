#include <stdio.h>
#include <hardware/i2c.h>
#include <hardware/gpio.h>
#include <pico/stdlib.h>
#include "I2CConnectionPicoSDK.h"
#include "SSD1305.h"
#include "ssd1305_smoke.h"

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
    static SSD1305Full chip(conn);
    ssd1305Checks(chip, report);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    while (true) sleep_ms(1000);
}
