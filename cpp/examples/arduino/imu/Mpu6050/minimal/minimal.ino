#include <Wire.h>
#include "I2CTransport.h"
#include "Mpu6050.h"

I2CTransport transport(Wire, 0x68);
MPU6050Minimal imu(transport);                           // Create MPU6050 driver, (transport, addr=0x68) → None

void setup() {
    Serial.begin(115200);
    Wire.begin();
}

void loop() {
    float ax, ay, az;
    float gx, gy, gz;
    imu.accel(ax, ay, az);                               // Read 3-axis acceleration, (x, y, z) → m/s²
    imu.gyro(gx, gy, gz);                                // Read 3-axis angular rate, (x, y, z) → rad/s
    Serial.print("accel: "); Serial.print(ax); Serial.print(" "); Serial.print(ay); Serial.print(" "); Serial.print(az);
    Serial.print("  gyro: "); Serial.print(gx); Serial.print(" "); Serial.print(gy); Serial.print(" "); Serial.println(gz);
    delay(100);
}
