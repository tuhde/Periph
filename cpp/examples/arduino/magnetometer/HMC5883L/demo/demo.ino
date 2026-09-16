#include <Wire.h>
#include "I2CConnection.h"
#include "HMC5883L.h"
#include <math.h>

I2CConnection connection(Wire, 0x1E);
HMC5883LFull hmc5883l(connection);

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin();

    // --- Configure for electronic compass ---
    // 8-sample averaging at 15 Hz suppresses noise; ±1.3 Ga gain covers Earth's field (~0.5 Ga).
    hmc5883l.configure(15, 8, 1);       // Configure chip, (odr 0.75-75 Hz, averaging 1/2/4/8, gain 0-7) → None

    Serial.println("Electronic compass demo — hold sensor flat, rotate horizontally");
    Serial.println("Vertical mount warning: |Z| > 30 uT indicates tilt compensation needed");
    Serial.println();

    // --- Sample and compute heading ---
    // User rotates the sensor horizontally; we compute heading from X/Y axes.
    // At n=5, user is prompted to tilt vertically to demonstrate Z-axis detection.
    for (int n = 0; n < 10; n++) {
        while (!hmc5883l.data_ready()) {  // Check data ready, () → bool
            delay(1);
        }
        float x, y, z;
        hmc5883l.magnetic_field(x, y, z);  // Read magnetic field, () → (float T, float T, float T)

        // --- Compute heading from X and Y ---
        if (!isnan(x) && !isnan(y)) {
            float heading = atan2(y, x) * 180.0 / PI;
            if (heading < 0) heading += 360;
            Serial.print("Heading: "); Serial.print(heading, 1); Serial.println(" deg");
        }

        // --- Vertical mount detection ---
        if (!isnan(z) && fabs(z) > 30e-6) {
            Serial.print("[TILT WARNING] Z="); Serial.print(z * 1e6, 1);
            Serial.println(" uT — tilt compensation needed");
        }

        if (n == 4) {
            Serial.println(">>> Now tilt sensor vertically <<<");
        }

        delay(500);
    }
}

void loop() {
    delay(1000);
}