#include <Wire.h>
#include "I2CConnection.h"
#include "ADXL345.h"

I2CConnection connection(Wire, 0x53);
ADXL345Minimal accel(connection);                       // Create ADXL345 driver, (connection, spi=false)

void setup() {
    Serial.begin(115200);
    Wire.begin();
}

void loop() {
    float x, y, z;
    accel.read(x, y, z);                                // Read 3-axis acceleration, (x, y, z) → g, g, g
    Serial.print(x, 3); Serial.print(" ");
    Serial.print(y, 3); Serial.print(" ");
    Serial.print(z, 3); Serial.println(" g");
    delay(100);
}
