#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x38);
BMA150Minimal accel(connection);                       // Create BMA150 driver, (connection)

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
