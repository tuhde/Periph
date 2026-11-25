#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x29);
BNO055Minimal imu(connection);                                          // Create BNO055 driver, (connection) → void

void setup() {
    Serial.begin(115200);
    Wire.begin();
}

void loop() {
    float h, r, p;
    imu.euler(h, r, p);                                                 // Read fused Euler angles, (heading, roll, pitch) → void rad
    Serial.print("heading "); Serial.print(h, 3); Serial.print(" rad, roll "); Serial.print(r, 3); Serial.print(", pitch "); Serial.print(p, 3); Serial.println();
    delay(100);
}
