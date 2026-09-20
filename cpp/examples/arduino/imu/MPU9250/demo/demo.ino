#include <Wire.h>
#include <math.h>
#include "I2CConnection.h"
#include "MPU9250.h"

I2CConnection connection(Wire, 0x68);

// --- Configure for noise-sensitive power rail monitoring ---
// 128-sample averaging suppresses switching noise on a noisy 5 V rail;
// continuous mode avoids re-triggering overhead between measurements.
MPU9250Full imu(connection);                          // Create MPU9250 driver, (connection) → void
imu.configure_accel(1);                               // Configure accel range, (full_scale=0) → void
imu.configure_gyro(1);                                // Configure gyro range, (full_scale=0) → void
imu.enable_mag(16, 6);                                // Initialize magnetometer, (bits=16, mode=6) → void

void setup() {
    Serial.begin(115200);
    delay(2000);
    Serial.println("roll     pitch    heading  |accel|  |gyro|");
}

void loop() {
    // gate reads on data_ready so each sample reflects a fresh conversion
    while (!imu.data_ready()) {                       // Check data ready flag, () → bool
    }

    float ax, ay, az, gx, gy, gz, mx, my, mz;
    imu.accel(ax, ay, az);                            // Read 3-axis acceleration, (float&, float&, float&) → void m/s²
    imu.gyro(gx, gy, gz);                             // Read 3-axis angular rate, (float&, float&, float&) → void rad/s
    imu.mag(mx, my, mz);                              // Read 3-axis magnetic field, (float&, float&, float&) → void µT

    // --- Compute tilt angles from the accelerometer gravity vector ---
    // roll and pitch are reliable when the device is quasi-static;
    // gyro magnitude indicates how fast the board is being rotated.
    float roll  = atan2(ay, az) * 180.0 / PI;
    float pitch = atan2(-ax, sqrt(ay * ay + az * az)) * 180.0 / PI;

    // --- Compute magnetic heading (simplified, no tilt compensation) ---
    // Magnetometer axes differ from accel/gyro axes; user must account for this in fusion.
    float heading = atan2(my, mx) * 180.0 / PI;

    float accel_mag = sqrt(ax * ax + ay * ay + az * az);
    float gyro_mag  = sqrt(gx * gx + gy * gy + gz * gz);

    Serial.print(roll, 1); Serial.print("      ");
    Serial.print(pitch, 1); Serial.print("      ");
    Serial.print(heading, 1); Serial.print("      ");
    Serial.print(accel_mag, 3); Serial.print("    ");
    Serial.println(gyro_mag, 3);
    delay(100);
}