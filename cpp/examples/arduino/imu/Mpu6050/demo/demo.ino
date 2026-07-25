#include <Wire.h>
#include <math.h>
#include "I2CTransport.h"
#include "Mpu6050.h"

I2CTransport transport(Wire, 0x68);
MPU6050Full imu(transport);

void setup() {
    Serial.begin(115200);
    Wire.begin();

    // --- Configure for motion logging with moderate dynamic range ---
    // ±4g captures typical tilting and handling forces without clipping;
    // ±500 dps covers fast rotations while retaining sub-degree resolution.
    imu.configure_accel(1);                               // Configure accel range, (full_scale=0) → None
    imu.configure_gyro(1);                                // Configure gyro range, (full_scale=0) → None

    Serial.println("roll     pitch    |accel|    |gyro|");
}

void loop() {
    // gate reads on data_ready so each sample reflects a fresh conversion
    while (!imu.data_ready()) {}                          // Check data ready flag, () → bool

    float ax, ay, az;
    float gx, gy, gz;
    imu.accel(ax, ay, az);                                // Read 3-axis acceleration, (x, y, z) → m/s²
    imu.gyro(gx, gy, gz);                                 // Read 3-axis angular rate, (x, y, z) → rad/s

    // --- Compute tilt angles from the accelerometer gravity vector ---
    // roll and pitch are reliable when the device is quasi-static;
    // gyro magnitude indicates how fast the board is being rotated.
    float roll  = atan2f(ay, az) * 180.0f / 3.141592653589793f;
    float pitch = atan2f(-ax, sqrtf(ay * ay + az * az)) * 180.0f / 3.141592653589793f;
    float accel_mag = sqrtf(ax * ax + ay * ay + az * az);
    float gyro_mag  = sqrtf(gx * gx + gy * gy + gz * gz);

    Serial.print(roll, 1);    Serial.print("  ");
    Serial.print(pitch, 1);   Serial.print("  ");
    Serial.print(accel_mag, 3); Serial.print("  ");
    Serial.println(gyro_mag, 3);

    delay(100);
}
