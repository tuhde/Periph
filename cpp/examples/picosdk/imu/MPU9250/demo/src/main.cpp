#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "hardware/i2c.h"
#include "I2CConnectionPicoSDK.h"
#include "MPU9250.h"

int main(void) {
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);

    I2CConnectionPicoSDK connection(i2c0, 0x68);

    // --- Configure for noise-sensitive power rail monitoring ---
    // 128-sample averaging suppresses switching noise on a noisy 5 V rail;
    // continuous mode avoids re-triggering overhead between measurements.
    MPU9250Full imu(connection);                      // Create MPU9250 driver, (connection) → void
    imu.configure_accel(1);                           // Configure accel range, (full_scale=0) → void
    imu.configure_gyro(1);                            // Configure gyro range, (full_scale=0) → void
    imu.enable_mag(16, 6);                            // Initialize magnetometer, (bits=16, mode=6) → void

    stdio_init_all();
    sleep_ms(2000);

    printf("roll     pitch    heading  |accel|  |gyro|\n");

    while (1) {
        // gate reads on data_ready so each sample reflects a fresh conversion
        while (!imu.data_ready()) {                   // Check data ready flag, () → bool
        }

        float ax, ay, az, gx, gy, gz, mx, my, mz;
        imu.accel(ax, ay, az);                        // Read 3-axis acceleration, (float&, float&, float&) → void m/s²
        imu.gyro(gx, gy, gz);                         // Read 3-axis angular rate, (float&, float&, float&) → void rad/s
        imu.mag(mx, my, mz);                          // Read 3-axis magnetic field, (float&, float&, float&) → void µT

        // --- Compute tilt angles from the accelerometer gravity vector ---
        // roll and pitch are reliable when the device is quasi-static;
        // gyro magnitude indicates how fast the board is being rotated.
        float roll  = atan2f(ay, az) * 180.0f / 3.141592653589793f;
        float pitch = atan2f(-ax, sqrtf(ay * ay + az * az)) * 180.0f / 3.141592653589793f;

        // --- Compute magnetic heading (simplified, no tilt compensation) ---
        // Magnetometer axes differ from accel/gyro axes; user must account for this in fusion.
        float heading = atan2f(my, mx) * 180.0f / 3.141592653589793f;

        float accel_mag = sqrtf(ax * ax + ay * ay + az * az);
        float gyro_mag  = sqrtf(gx * gx + gy * gy + gz * gz);

        printf("%.1f      %.1f      %.1f      %.3f    %.3f\n", roll, pitch, heading, accel_mag, gyro_mag);
        sleep_ms(100);
    }
    return 0;
}