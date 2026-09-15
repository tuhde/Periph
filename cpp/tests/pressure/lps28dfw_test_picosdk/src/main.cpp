#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "LPS28DFW.h"

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
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x5C);
    LPS28DFWFull lps(connection);

    stdio_init_all();
    sleep_ms(2000);
    check_near(lps.read_pressure(), 260.0f, 1260.0f, "pressure range");
    check_near(lps.read_temperature(), -40.0f, 85.0f, "temperature range");

    lps.configure(LPS28DFWFull::ODR_100_HZ, LPS28DFWFull::AVG_128,
                  LPS28DFWFull::FS_MODE_2, 0, 1);
    check_true(lps._fs_mode == 1, "fs_mode_2_set");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}