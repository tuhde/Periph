#include <Wire.h>
#include "I2CTransport.h"
#include "APDS9960.h"

I2CTransport transport(Wire, 0x39);
APDS9960Full apds(transport);                              // Create APDS9960 driver, (transport) → APDS9960Full

void setup() {
    Serial.begin(115200);
    Wire.begin();

    // --- Monitor ambient light with adaptive integration time ---
    // Start with the default 200 ms integration (ATIME=0xB6). When the clear
    // channel approaches saturation, halve the integration time to prevent overflow.
    apds.configure_als(0xB6, 1);                           // Configure ALS, (atime 0-255, again 0-3) → void
}

void loop() {
    while (!apds.is_als_valid()) {                         // Check ALS data valid, () → bool
        delay(10);
    }

    uint16_t c, r, g, b;
    apds.color(c, r, g, b);                                // Read all RGBC channels, (clear, red, green, blue) → void
    float lux = -0.32466f * r + 1.57837f * g + -0.73191f * b;
    Serial.print("C="); Serial.print(c);
    Serial.print(" R="); Serial.print(r);
    Serial.print(" G="); Serial.print(g);
    Serial.print(" B="); Serial.print(b);
    Serial.print("  lux~"); Serial.println(lux, 0);

    // --- Adaptive integration: reduce time when saturated ---
    // At saturation the sensor clips; shortening integration recovers
    // headroom at the cost of reduced sensitivity in low light.
    if (apds.is_als_saturated()) {                         // Check ALS saturated, () → bool
        Serial.println("[SATURATED — reducing integration time]");
        apds.configure_als(0xFE, 1);                       // Configure ALS, (atime 0-255, again 0-3) → void
        delay(250);
    }

    delay(1000);
}
