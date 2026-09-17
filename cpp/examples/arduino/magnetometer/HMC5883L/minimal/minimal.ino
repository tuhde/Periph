#include <Wire.h>
#include "I2CConnection.h"
#include "HMC5883L.h"

I2CConnection connection(Wire, 0x1E);
HMC5883LMinimal hmc5883l(connection);

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin();
    delay(6);  // Wait for first measurement
}

void loop() {
    float x, y, z;
    hmc5883l.magnetic_field(x, y, z);  // Read magnetic field, () → (float T, float T, float T)
    Serial.print("X="); Serial.print(x, 6); Serial.print(" T  Y=");
    Serial.print(y, 6); Serial.print(" T  Z=");
    Serial.print(z, 6); Serial.println(" T");
    delay(1000);
}