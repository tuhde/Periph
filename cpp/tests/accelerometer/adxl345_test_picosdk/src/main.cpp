#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "ADXL345.h"

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 400 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);

    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate

    I2CConnectionPicoSDK connection(i2c0, 0x53);
    ADXL345Minimal accel(connection);                       // Create ADXL345 driver, (connection, spi=false)

    float x, y, z;
    accel.read(x, y, z);                                    // Read 3-axis acceleration, (x, y, z) → g, g, g
    check_true(x == x && y == y && z == z, "read_returns_floats");
    float mag = sqrtf(x * x + y * y + z * z);
    check_true(mag >= 0.5f && mag <= 1.5f, "magnitude_near_1g");

    ADXL345Full accel_full(connection);                     // Create ADXL345 Full driver, (connection, spi=false)
    accel_full.set_range(4);                                // Set measurement range, (range_g) → g
    accel_full.read(x, y, z);                               // Read 3-axis acceleration, (x, y, z) → g, g, g
    check_true(x == x && y == y && z == z, "read_after_set_range_4g");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
