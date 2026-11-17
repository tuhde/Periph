#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x6A);                                   // Create I²C connection, (Wire, addr=0x6A) → Connection
LTC4151Minimal ltc(connection, 0.02f);                                  // Create LTC4151 driver, (connection, r_sense=0.02 Ω) → void

void setup() {
    Serial.begin(115200);
    Wire.begin();
}

void loop() {
    Serial.print("V="); Serial.print(ltc.voltage(), 3); Serial.println(" V");  // Read supply voltage, () → float V
    Serial.print("I="); Serial.print(ltc.current(), 4); Serial.println(" A");  // Read load current, () → float A
    Serial.print("P="); Serial.print(ltc.power(), 3); Serial.println(" W");  // Read load power, () → float W
    delay(1000);
}
