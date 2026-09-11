// Demo for the APDS-9930 — adaptive backlight + screen-lock scenario.

#include <Wire.h>
#include "I2CConnection.h"
#include "Apds9930.h"

#define DIM_LUX_THRESHOLD 10.0f
#define PROX_SCREEN_OFF  400

I2CConnection connection(Wire, 0x39);                                  // Create I2C connection, (Wire, addr=0x39) → I2CConnection
APDS9930Full apds(connection);                                          // Create APDS-9930 Full, (connection) → APDS9930Full
                                                                        // default 101 ms ALS integration, 8-pulse proximity, 100 mA drive

void setup() {
    Serial.begin(115200);
    Wire.begin();
    delay(110);
}

void loop() {
    delay(1000);
    // --- Adaptive backlight + screen-lock monitoring ---
    // 101 ms ALS integration rejects 50/60 Hz fluorescent flicker, and
    // 8 pulses at 100 mA gives reliable readings to ~100 mm.
    float lx = apds.lux();                                             // Read ambient illuminance, () → float lx
                                                                        // IR-compensated lux via Ch0/Ch1 difference
    uint16_t p = apds.proximity();                                     // Read proximity count, () → uint16_t count
                                                                        // 16-bit ADC value; higher = closer
    Serial.print("lux="); Serial.print(lx, 1);
    Serial.print(" lx  proximity="); Serial.println(p);
    if (lx < DIM_LUX_THRESHOLD) {
        Serial.println("  -> dim backlight");
    }
    if (p > PROX_SCREEN_OFF) {
        Serial.println("  -> disable screen");
    }
}