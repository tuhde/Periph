#include <Wire.h>
#include <Periph.h>

I2CConnection accelConnection(Wire, 0x19);
I2CConnection magConnection(Wire, 0x1E);
LSM303Minimal sensor(accelConnection, magConnection);    // Create LSM303 driver, (accelConnection, magConnection)

void setup() {
    Serial.begin(115200);
    Wire.begin();
}

void loop() {
    float ax, ay, az;
    sensor.read_acceleration(ax, ay, az);                // Read 3-axis acceleration, (x, y, z) → g, g, g
    float mx, my, mz;
    sensor.read_magnetic(mx, my, mz);                    // Read 3-axis magnetic field, (x, y, z) → µT, µT, µT
    Serial.print("a: ");
    Serial.print(ax, 3); Serial.print(" ");
    Serial.print(ay, 3); Serial.print(" ");
    Serial.print(az, 3); Serial.print(" g   m: ");
    Serial.print(mx, 1); Serial.print(" ");
    Serial.print(my, 1); Serial.print(" ");
    Serial.print(mz, 1); Serial.println(" uT");
    delay(100);
}
