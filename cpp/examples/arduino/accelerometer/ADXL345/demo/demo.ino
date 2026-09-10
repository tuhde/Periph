#include <Wire.h>
#include <math.h>
#include "I2CConnection.h"
#include "ADXL345.h"

I2CConnection connection(Wire, 0x53);
ADXL345Minimal accel(connection);                       // Create ADXL345 driver, (connection, spi=false)

// --- Stationary tilt characterization at 10 Hz ---
// With the sensor flat and the Z axis up, gravity should project entirely
// onto Z. Tilting the board visibly redistributes the 1 *g* magnitude
// across X and Y; the total vector magnitude stays near 1 *g*.
float mag_min = 1e9f, mag_max = -1e9f;

void setup() {
    Serial.begin(115200);
    Wire.begin();
}

void loop() {
    float x, y, z;
    accel.read(x, y, z);                                // Read 3-axis acceleration, (x, y, z) → g, g, g
    float mag = sqrtf(x * x + y * y + z * z);
    if (mag < mag_min) mag_min = mag;
    if (mag > mag_max) mag_max = mag;

    Serial.print("x="); Serial.print(x, 3);
    Serial.print("  y="); Serial.print(y, 3);
    Serial.print("  z="); Serial.print(z, 3);
    Serial.print("  |a|="); Serial.print(mag, 3);
    Serial.print(" g  (min="); Serial.print(mag_min, 3);
    Serial.print(" max="); Serial.print(mag_max, 3);
    Serial.println(")");

    delay(100);
}
