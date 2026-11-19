#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x54);
HS40xxMinimal sensor(connection);                                   // Create HS40xx driver, (connection) → void

void setup() {
    Serial.begin(115200);
    Wire.begin();
}

void loop() {
    float t = 0, rh = 0;
    bool ok = sensor.read(t, rh);                                   // Read temperature and humidity, (t, rh) → bool °C, %RH
    Serial.print(t, 2); Serial.print(" C, "); Serial.print(rh, 1); Serial.print(" %RH (ok="); Serial.print((unsigned long)(ok)); Serial.print(")"); Serial.println();
    delay(2000);
}
