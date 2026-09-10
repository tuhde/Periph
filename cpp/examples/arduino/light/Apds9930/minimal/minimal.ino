#include <Wire.h>
#include "I2CConnection.h"
#include "Apds9930.h"

I2CConnection connection(Wire, 0x39);                                  // Create I2C connection, (Wire, addr=0x39) → I2CConnection
APDS9930Minimal apds(connection);                                       // Create APDS-9930 Minimal, (connection) → APDS9930Minimal
                                                                        // initialises with ATIME=0xDB, PTIME=0xFF, PPULSE=8, CONTROL=0x20

void setup() {
    Serial.begin(115200);
    Wire.begin();
    delay(110);
}

void loop() {
    float lx = apds.lux();                                             // Read ambient illuminance, () → float lx
                                                                        // IR-compensated lux via Ch0/Ch1 difference
    uint16_t p = apds.proximity();                                     // Read proximity count, () → uint16_t count
                                                                        // 16-bit ADC value; higher = closer object
    Serial.print("lux="); Serial.print(lx, 1);
    Serial.print(" lx  proximity="); Serial.println(p);
    delay(1000);
}