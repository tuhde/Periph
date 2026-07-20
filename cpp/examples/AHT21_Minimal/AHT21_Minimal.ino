#include <Wire.h>
#include "I2CTransport.h"
#include "AHT21.h"

I2CTransport transport(Wire, 0x38);
AHT21Minimal aht(transport);                                           // Create AHT21 driver, (transport, addr=0x38) → void

void setup() {
    Serial.begin(115200);
    Wire.begin();
}

void loop() {
    float t, h;
    aht.read(t, h);                                                    // Trigger measurement, (temperature_c, humidity_pct) → void
    Serial.print(t);   Serial.print(" C  ");
    Serial.print(h);   Serial.println(" %RH");
    delay(1000);
}
