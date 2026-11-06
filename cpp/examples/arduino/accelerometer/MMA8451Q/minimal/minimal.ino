#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x1C);
MMA8451QMinimal accel(connection);                       // Create MMA8451Q driver, (connection)

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
