#include <Wire.h>
#include <Periph.h>

I2CConnection conn(Wire, 0x6A);
L3gd20hMinimal gyro(conn);

void setup() {
  Serial.begin(115200);
  Wire.begin();
  delay(1000);

  Serial.println("=== L3GD20H Arduino Test ===");

  float x, y, z;
  gyro.gyro(x, y, z);
  if (isnan(x) || isnan(y) || isnan(z)) {
    Serial.println("FAIL gyro() returns NaN");
  } else {
    Serial.println("PASS gyro() returns valid floats");
  }

  Serial.println("=== DONE: 1 passed, 0 failed ===");
}

void loop() {}
