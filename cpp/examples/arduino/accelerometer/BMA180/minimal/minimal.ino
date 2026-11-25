#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x40);
BMA180Minimal accel(connection);                       // Create BMA180 driver, (connection)

void setup() {
    Serial.begin(115200);
    Wire.begin();
    delay(2000);
}

void loop() {
    float x, y, z;
    accel.read(x, y, z);                                   // Read 3-axis acceleration, (x, y, z) → g, g, g
    Serial.print("x="); Serial.print(x, 3);
    Serial.print(" y="); Serial.print(y, 3);
    Serial.print(" z="); Serial.print(z, 3);
    Serial.println(" g");
    delay(100);
}