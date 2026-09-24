#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x5A);                                    // Create I2C connection, (Wire, addr=0x5A) → I2CConnection
MPR121Minimal mpr(connection);                                          // Create MPR121 Minimal, (connection) → MPR121Minimal
                                                                        // resets, applies default thresholds (T=12, R=6), enters Run Mode on all 12 electrodes

void setup() {
    Serial.begin(115200);
    Wire.begin();
}

void loop() {
    uint16_t t = mpr.touched();                                         // Read 12-bit touch bitmask, () → uint16_t bitmask
                                                                        // bit n=1 means ELEn is currently touched
    Serial.print("touched=0x"); Serial.println(t, HEX);
    delay(1000);
}
