#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

#include <Arduino.h>
#include <Wire.h>
#include <math.h>
#include "../../src/connection/I2CConnection.h"
#include "../../src/chips/accelerometer/ADXL345.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { Serial.print("PASS "); Serial.println(label); passed++; }
    else       { Serial.print("FAIL "); Serial.println(label); failed++; }
}

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    I2CConnection connection(Wire, 0x53);
    ADXL345Minimal accel(connection);                       // Create ADXL345 driver, (connection, spi=false)

    // --- 50-sample stationary tilt characterization at 10 Hz ---
    // With the sensor flat and the Z axis up, gravity should project entirely
    // onto Z. Tilting the board visibly redistributes the 1 *g* magnitude
    // across X and Y; the total vector magnitude stays near 1 *g*.
    const int SAMPLES = 50;
    const int PERIOD_MS = 100;

    float mag_min = 1e9f, mag_max = -1e9f;

    for (int n = 0; n < SAMPLES; n++) {
        float x, y, z;
        accel.read(x, y, z);                                // Read 3-axis acceleration, (x, y, z) → g, g, g
        float mag = sqrtf(x * x + y * y + z * z);
        if (mag < mag_min) mag_min = mag;
        if (mag > mag_max) mag_max = mag;
        Serial.print(n);
        Serial.print("  x="); Serial.print(x, 3);
        Serial.print("  y="); Serial.print(y, 3);
        Serial.print("  z="); Serial.print(z, 3);
        Serial.print("  |a|="); Serial.print(mag, 3);
        Serial.println(" g");
        delay(PERIOD_MS);
    }

    Serial.print("min |a|=");
    Serial.print(mag_min, 3);
    Serial.print(" g  max |a|=");
    Serial.print(mag_max, 3);
    Serial.println(" g");

    Serial.print("===DONE: ");
    Serial.print(passed);
    Serial.print(" passed, ");
    Serial.print(failed);
    Serial.println(" failed===");
}

void loop() { delay(1000); }