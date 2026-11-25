#include <Wire.h>
#include <Periph.h>
#include <math.h>

I2CConnection connection(Wire, 0x5C);
AM2315Minimal sensor(connection);                                        // Create AM2315 driver, (connection) → void

void setup() {
    Serial.begin(115200);
    Wire.begin();
    Wire.setClock(100000);                                              // standard mode only (<= 100 kHz)
}

void loop() {
    float t = 0, rh = 0;
    bool ok = sensor.read(t, rh);                                            // Read temperature and humidity, (t, rh) → bool °C, %RH
                                                                             // previous measurement; reads are kept >= 2 s apart
    Serial.print(t, 1); Serial.print(" C, "); Serial.print(rh, 1); Serial.print(" %RH (ok="); Serial.print((unsigned)(ok)); Serial.print(")"); Serial.println();
    delay(2000);
}
