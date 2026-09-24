#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x68);
MPU9250Minimal imu(connection);

void setup() {
    Serial.begin(115200);
    delay(2000);
}

void loop() {
    float ax, ay, az, gx, gy, gz;
    imu.accel(ax, ay, az);                            // Read 3-axis acceleration, (float&, float&, float&) → void m/s²
    imu.gyro(gx, gy, gz);                             // Read 3-axis angular rate, (float&, float&, float&) → void rad/s
    Serial.print("accel: "); Serial.print(ax, 2); Serial.print(" "); Serial.print(ay, 2); Serial.print(" "); Serial.print(az, 2);
    Serial.print("  gyro: "); Serial.print(gx, 2); Serial.print(" "); Serial.print(gy, 2); Serial.print(" "); Serial.println(gz, 2);
    delay(100);
}
