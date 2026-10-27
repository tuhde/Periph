#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "LSM303.h"

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

    I2CConnectionPicoSDK accel_connection(i2c0, 0x19);
    I2CConnectionPicoSDK mag_connection(i2c0, 0x1E);

    LSM303Minimal sensor(accel_connection, mag_connection);  // Create LSM303 driver, (accel, mag connections)
    LSM303Full sensor_full(accel_connection, mag_connection);  // Create LSM303 Full driver, (accel, mag connections)

    stdio_init_all();
    sleep_ms(2000);

    float ax, ay, az;
    sensor.read_acceleration(ax, ay, az);                     // Read 3-axis acceleration, (x, y, z) → g, g, g
    check_true(ax == ax && ay == ay && az == az, "read_acceleration_returns_floats");
    float amag = sqrtf(ax * ax + ay * ay + az * az);
    check_true(amag >= 0.5f && amag <= 1.5f, "acceleration_magnitude_near_1g");

    float mx, my, mz;
    sensor.read_magnetic(mx, my, mz);                         // Read 3-axis magnetic field, (x, y, z) → µT, µT, µT
    check_true(mx == mx && my == my && mz == mz, "read_magnetic_returns_floats");
    float bmag = sqrtf(mx * mx + my * my + mz * mz);
    check_true(bmag >= 5.0f && bmag <= 200.0f, "magnetic_magnitude_plausible");

    sensor_full.set_range(4);
    sensor_full.read_acceleration(ax, ay, az);
    check_true(ax == ax && ay == ay && az == az, "read_acceleration_after_set_range_4g");

    sensor_full.set_mag_range(470);
    sensor_full.read_magnetic(mx, my, mz);
    check_true(mx == mx && my == my && mz == mz, "read_magnetic_after_set_mag_range");

    float hdg = sensor_full.heading();                        // Compute compass heading, (tilt_compensate=true) → degrees
    check_true(hdg >= 0.0f && hdg < 360.0f, "heading_in_range");

    float temp = sensor_full.read_temperature_delta();
    check_true(temp == temp, "temperature_delta_is_finite");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
