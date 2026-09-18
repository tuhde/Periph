#include <Wire.h>
#include <I2CConnection.h>
#include <L3gd20h.h>
#include <math.h>

I2CConnection conn(Wire, 0x6A);
L3gd20hFull gyro(conn);

void setup() {
  Serial.begin(115200);
  Wire.begin();

  // --- Configure for shake detection at 190 Hz, ±500 dps ---
  // 190 Hz ODR provides good temporal resolution for shake detection;
  // ±500 dps full scale gives 17.5 mdps/digit sensitivity, suitable for
  // detecting moderate to strong motion without clipping.
  gyro.configure(L3gd20hFull::ODR_190_HZ, 0, 1);  // Configure, (odr 0-3, bw 0-3, full_scale 0-2) -> None

  Serial.println("L3GD20H shake detector running. Shake the device...");
}

void loop() {
  if (gyro.data_ready()) {                         // Check data ready, () -> bool
    float x, y, z;
    gyro.gyro(x, y, z);                            // Read angular rate, () -> (float, float, float) rad/s
    float magnitude = sqrt(x*x + y*y + z*z);
    if (magnitude > 1.0) {
      Serial.print("SHAKE DETECTED: mag="); Serial.print(magnitude, 3);
      Serial.print(" (x="); Serial.print(x, 3);
      Serial.print(" y="); Serial.print(y, 3);
      Serial.print(" z="); Serial.print(z, 3);
      Serial.println(")");
    } else {
      Serial.print("x="); Serial.print(x, 3);
      Serial.print(" y="); Serial.print(y, 3);
      Serial.print(" z="); Serial.print(z, 3);
      Serial.print(" mag="); Serial.println(magnitude, 3);
    }
  }
}