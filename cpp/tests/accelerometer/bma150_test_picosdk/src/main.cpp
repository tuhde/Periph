#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "BMA150.h"

int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main(void) {
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);

    I2CConnectionPicoSDK connection(i2c0, 0x38);
    BMA150Minimal accel(connection);                       // Create BMA150 driver, (connection)
    BMA150Full accel_full(connection);                     // Create BMA150 Full driver, (connection)

    stdio_init_all();
    sleep_ms(2000);

    float x, y, z;
    accel.read(x, y, z);                                    // Read 3-axis acceleration, (x, y, z) → g, g, g
    check_true(x == x && y == y && z == z, "read_returns_floats");

    accel_full.set_range(4);
    accel_full.read(x, y, z);
    check_true(x == x && y == y && z == z, "read_after_set_range_4g");

    float temp = accel_full.read_temperature();
    check_true(temp >= -30.0f && temp <= 97.5f, "temperature_in_range");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
