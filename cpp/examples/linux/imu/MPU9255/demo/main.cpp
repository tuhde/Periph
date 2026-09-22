#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <ctime>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "MPU9255.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x68;
    I2CConnectionLinux connection(bus, addr);
    I2CConnectionLinux magConnection(bus, 0x0C);                          // AK8963, same bus, reached via I²C bypass

    // --- Configure for motion-triggered wake logger ---
    // 64 mg threshold and 31.25 Hz wake-up rate balance sensitivity against spurious
    // wake-ups from vibration; once motion fires, the full 6-axis sensor suite
    // (gyro + mag at 100 Hz) is re-enabled to capture a 5-second tilt/heading burst.
    MPU9255Full imu(connection, magConnection);                          // Create MPU9255 driver, (connection, magConnection)
    imu.configure_wake_on_motion(64, 31.25f);                            // Configure wake-on-motion, (threshold_mg=64, odr_hz=31.25) → void

    timespec last_heartbeat;
    clock_gettime(CLOCK_MONOTONIC, &last_heartbeat);

    while (true) {
        // --- Idle phase: motion poll at ~5 Hz, "sleeping…" heartbeat at ~1 Hz ---
        // configure_wake_on_motion already disabled the gyro and put the chip
        // in CYCLE=1 duty-cycled mode; polling motion_detected() reflects that
        // state without forcing any further register writes.
        while (!imu.motion_detected()) {                                 // Check motion detected, () → bool
            timespec now;
            clock_gettime(CLOCK_MONOTONIC, &now);
            double elapsed = (now.tv_sec - last_heartbeat.tv_sec) + (now.tv_nsec - last_heartbeat.tv_nsec) / 1e9;
            if (elapsed >= 1.0) {
                printf("sleeping...\n");
                last_heartbeat = now;
            }
            usleep(200000);
        }

        // --- Wake phase: re-arm the full 6-axis + mag stack ---
        // PWR_MGMT_1=0x01 clears CYCLE; PWR_MGMT_2=0x00 re-enables all three gyro axes.
        imu.set_sleep(false);                                            // Wake from sleep, (sleep=true) → void
        imu.configure_gyro(1);                                           // Configure gyro range, (full_scale=0) → void
        imu.configure_accel(1);                                          // Configure accel range, (full_scale=0) → void
        imu.enable_mag(16, 6);                                           // Initialize magnetometer, (bits=16, mode=6) → void

        // --- Capture a 5-second tilt/heading burst at ~10 Hz ---
        // Roll/pitch from gravity (quasi-static) + heading from mag (no tilt comp).
        printf("--- motion detected ---\n");
        timespec end;
        clock_gettime(CLOCK_MONOTONIC, &end);
        end.tv_sec += 5;
        while (true) {
            timespec now;
            clock_gettime(CLOCK_MONOTONIC, &now);
            if (now.tv_sec > end.tv_sec || (now.tv_sec == end.tv_sec && now.tv_nsec >= end.tv_nsec)) {
                break;
            }
            while (!imu.data_ready()) {}                                // Check data ready flag, () → bool

            float ax, ay, az, gx, gy, gz, mx, my, mz;
            imu.accel(ax, ay, az);                                       // Read 3-axis acceleration, (float&, float&, float&) → void m/s²
            imu.gyro(gx, gy, gz);                                        // Read 3-axis angular rate, (float&, float&, float&) → void rad/s
            imu.mag(mx, my, mz);                                         // Read 3-axis magnetic field, (float&, float&, float&) → void µT

            float roll  = atan2f(ay, az) * 180.0f / static_cast<float>(M_PI);
            float pitch = atan2f(-ax, sqrtf(ay * ay + az * az)) * 180.0f / static_cast<float>(M_PI);
            float heading = atan2f(my, mx) * 180.0f / static_cast<float>(M_PI);

            printf("%-8.1f %-8.1f %-8.1f  |g|=%.2f\n", roll, pitch, heading, sqrtf(gx * gx + gy * gy + gz * gz));
            usleep(100000);
        }

        // --- Return to low-power wake-on-motion mode ---
        imu.configure_wake_on_motion(64, 31.25f);                        // Configure wake-on-motion, (threshold_mg=64, odr_hz=31.25) → void
        clock_gettime(CLOCK_MONOTONIC, &last_heartbeat);
    }
    return 0;
}