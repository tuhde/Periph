#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "hardware/i2c.h"
#include "I2CConnectionPicoSDK.h"
#include "MPU9255.h"

int main(void) {
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);

    I2CConnectionPicoSDK connection(i2c0, 0x68);
    I2CConnectionPicoSDK magConnection(i2c0, 0x0C);   // AK8963, same bus, reached via I²C bypass

    // --- Configure for motion-triggered wake logger ---
    // 64 mg threshold and 31.25 Hz wake-up rate balance sensitivity against spurious
    // wake-ups from vibration; once motion fires, the full 6-axis sensor suite
    // (gyro + mag at 100 Hz) is re-enabled to capture a 5-second tilt/heading burst.
    MPU9255Full imu(connection, magConnection);       // Create MPU9255 driver, (connection, magConnection) → void
    imu.configure_wake_on_motion(64, 31.25f);         // Configure wake-on-motion, (threshold_mg=64, odr_hz=31.25) → void

    stdio_init_all();
    sleep_ms(2000);

    uint32_t last_heartbeat = to_ms_since_boot(get_absolute_time());

    while (1) {
        // --- Idle phase: motion poll at ~5 Hz, "sleeping…" heartbeat at ~1 Hz ---
        // configure_wake_on_motion already disabled the gyro and put the chip
        // in CYCLE=1 duty-cycled mode; polling motion_detected() reflects that
        // state without forcing any further register writes.
        while (!imu.motion_detected()) {               // Check motion detected, () → bool
            uint32_t now = to_ms_since_boot(get_absolute_time());
            if (now - last_heartbeat >= 1000) {
                printf("sleeping...\n");
                last_heartbeat = now;
            }
            sleep_ms(200);
        }

        // --- Wake phase: re-arm the full 6-axis + mag stack ---
        // PWR_MGMT_1=0x01 clears CYCLE; PWR_MGMT_2=0x00 re-enables all three gyro axes.
        imu.set_sleep(false);                          // Wake from sleep, (sleep=true) → void
        imu.configure_gyro(1);                         // Configure gyro range, (full_scale=0) → void
        imu.configure_accel(1);                        // Configure accel range, (full_scale=0) → void
        imu.enable_mag(16, 6);                         // Initialize magnetometer, (bits=16, mode=6) → void

        // --- Capture a 5-second tilt/heading burst at ~10 Hz ---
        // Roll/pitch from gravity (quasi-static) + heading from mag (no tilt comp).
        printf("--- motion detected ---\n");
        uint32_t end = to_ms_since_boot(get_absolute_time()) + 5000;
        while (to_ms_since_boot(get_absolute_time()) < end) {
            while (!imu.data_ready()) {}               // Check data ready flag, () → bool

            float ax, ay, az, gx, gy, gz, mx, my, mz;
            imu.accel(ax, ay, az);                     // Read 3-axis acceleration, (float&, float&, float&) → void m/s²
            imu.gyro(gx, gy, gz);                      // Read 3-axis angular rate, (float&, float&, float&) → void rad/s
            imu.mag(mx, my, mz);                       // Read 3-axis magnetic field, (float&, float&, float&) → void µT

            float roll  = atan2f(ay, az) * 180.0f / 3.141592653589793f;
            float pitch = atan2f(-ax, sqrtf(ay * ay + az * az)) * 180.0f / 3.141592653589793f;
            float heading = atan2f(my, mx) * 180.0f / 3.141592653589793f;

            printf("%.1f      %.1f      %.1f      |g|=%.2f\n",
                   roll, pitch, heading, sqrtf(gx * gx + gy * gy + gz * gz));
            sleep_ms(100);
        }

        // --- Return to low-power wake-on-motion mode ---
        imu.configure_wake_on_motion(64, 31.25f);      // Configure wake-on-motion, (threshold_mg=64, odr_hz=31.25) → void
        last_heartbeat = to_ms_since_boot(get_absolute_time());
    }
    return 0;
}