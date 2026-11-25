#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x38
#endif

#include <stdio.h>
#include <math.h>
#include "I2CConnectionLinux.h"
#include "BMA150.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main() {
    I2CConnectionLinux connection(TEST_I2C_BUS, TEST_ADDR);

    BMA150Minimal accel(connection);                       // Create BMA150 driver, (connection)

    float x, y, z;
    accel.read(x, y, z);                                    // Read 3-axis acceleration, (x, y, z) → g, g, g
    check_true(x == x && y == y && z == z, "read_returns_floats");
    float mag = sqrtf(x * x + y * y + z * z);
    check_true(mag >= 0.5f && mag <= 1.5f, "magnitude_near_1g");

    BMA150Full accel_full(connection);                     // Create BMA150 Full driver, (connection)
    accel_full.set_range(4);
    accel_full.read(x, y, z);
    check_true(x == x && y == y && z == z, "read_after_set_range_4g");

    float temp = accel_full.read_temperature();
    check_true(temp >= -30.0f && temp <= 97.5f, "temperature_in_range");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
