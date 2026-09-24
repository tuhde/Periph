#include <Wire.h>
#include <math.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x68);
I2CConnection magConnection(Wire, 0x0C);              // AK8963, same bus, reached via I²C bypass

MPU9255Full imu(connection, magConnection);           // Create MPU9255 driver, (connection, magConnection) → void

void setup() {
    Serial.begin(115200);
    delay(2000);

    // --- Configure for motion-triggered wake logger ---
    // 64 mg threshold and 31.25 Hz wake-up rate balance sensitivity against spurious
    // wake-ups from vibration; once motion fires, the full 6-axis sensor suite
    // (gyro + mag at 100 Hz) is re-enabled to capture a 5-second tilt/heading burst.
    imu.configure_wake_on_motion(64, 31.25f);             // Configure wake-on-motion, (threshold_mg=64, odr_hz=31.25) → void

    Serial.println("waiting for motion...");
}

void loop() {
    // --- Idle phase: motion poll at ~5 Hz, "sleeping…" heartbeat at ~1 Hz ---
    // configure_wake_on_motion already disabled the gyro and put the chip
    // in CYCLE=1 duty-cycled mode; polling motion_detected() reflects that
    // state without forcing any further register writes.
    static unsigned long last_heartbeat = 0;
    while (!imu.motion_detected()) {                  // Check motion detected, () → bool
        if (millis() - last_heartbeat >= 1000) {
            Serial.println("sleeping...");
            last_heartbeat = millis();
        }
        delay(200);
    }

    // --- Wake phase: re-arm the full 6-axis + mag stack ---
    // PWR_MGMT_1=0x01 clears CYCLE; PWR_MGMT_2=0x00 re-enables all three gyro axes.
    imu.set_sleep(false);                             // Wake from sleep, (sleep=true) → void
    imu.configure_gyro(1);                            // Configure gyro range, (full_scale=0) → void
    imu.configure_accel(1);                           // Configure accel range, (full_scale=0) → void
    imu.enable_mag(16, 6);                            // Initialize magnetometer, (bits=16, mode=6) → void

    // --- Capture a 5-second tilt/heading burst at ~10 Hz ---
    // Roll/pitch from gravity (quasi-static) + heading from mag (no tilt comp).
    Serial.println("--- motion detected ---");
    unsigned long end = millis() + 5000;
    while ((long)(end - millis()) > 0) {
        while (!imu.data_ready()) {                   // Check data ready flag, () → bool
        }

        float ax, ay, az, gx, gy, gz, mx, my, mz;
        imu.accel(ax, ay, az);                        // Read 3-axis acceleration, (float&, float&, float&) → void m/s²
        imu.gyro(gx, gy, gz);                         // Read 3-axis angular rate, (float&, float&, float&) → void rad/s
        imu.mag(mx, my, mz);                          // Read 3-axis magnetic field, (float&, float&, float&) → void µT

        float roll  = atan2(ay, az) * 180.0 / PI;
        float pitch = atan2(-ax, sqrt(ay * ay + az * az)) * 180.0 / PI;
        float heading = atan2(my, mx) * 180.0 / PI;

        Serial.print(roll, 1); Serial.print("      ");
        Serial.print(pitch, 1); Serial.print("      ");
        Serial.print(heading, 1); Serial.print("      ");
        Serial.print("|g|="); Serial.println(sqrt(gx * gx + gy * gy + gz * gz), 2);
        delay(100);
    }

    // --- Return to low-power wake-on-motion mode ---
    imu.configure_wake_on_motion(64, 31.25f);         // Configure wake-on-motion, (threshold_mg=64, odr_hz=31.25) → void
    last_heartbeat = millis();
}
