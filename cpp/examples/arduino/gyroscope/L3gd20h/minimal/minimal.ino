#include <Wire.h>
#include <I2CConnection.h>
#include <L3gd20h.h>

I2CConnection conn(Wire, 0x6A);
L3gd20hMinimal gyro(conn);

void setup() {
  Serial.begin(115200);
  Wire.begin();
}

void loop() {
  float x, y, z;
  gyro.gyro(x, y, z);  // Read angular rate, () -> (float, float, float) rad/s
  Serial.print("x="); Serial.print(x, 3);
  Serial.print(" y="); Serial.print(y, 3);
  Serial.print(" z="); Serial.println(z, 3);
  delay(100);
}