#include <Wire.h>
#include "I2CTransport.h"
#include "APDS9960.h"

I2CTransport transport(Wire, 0x39);
APDS9960Minimal apds(transport);                           // Create APDS9960 driver, (transport) → APDS9960Minimal

void setup() {
    Serial.begin(115200);
    Wire.begin();
}

void loop() {
    uint16_t c, r, g, b;
    apds.color(c, r, g, b);                                // Read all RGBC channels, (clear, red, green, blue) → void
    Serial.print("C="); Serial.print(c);
    Serial.print(" R="); Serial.print(r);
    Serial.print(" G="); Serial.print(g);
    Serial.print(" B="); Serial.println(b);
    delay(1000);
}
