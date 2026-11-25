#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x23);
BH1750Minimal sensor(connection);                               // Create BH1750 driver, (connection) → void

void setup() {
    Serial.begin(115200);
    Wire.begin();
    sensor.init();                                                  // Power on, () → void
}

void loop() {
    float lux = 0;
    bool ok = sensor.read(lux);                                     // Measure illuminance, (lux&) → bool
    Serial.print("illuminance "); Serial.print(lux, 1); Serial.print(" lx (ok="); Serial.print((unsigned long)(ok)); Serial.print(")"); Serial.println();
    delay(1000);
}
