#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x40);
HTU31DMinimal sensor(connection);                               // Create HTU31D driver, (connection) → void

void setup() {
    Serial.begin(115200);
    Wire.begin();
    sensor.init();                                                  // Soft reset, () → void
}

void loop() {
    float t = 0, rh = 0;
    bool ok = sensor.read(t, rh);                                   // Read temperature and humidity, (temperature_c&, humidity_pct&) → bool
    Serial.print("temperature "); Serial.print(t, 2); Serial.print(" C humidity "); Serial.print(rh, 1); Serial.print(" %RH (ok="); Serial.print((unsigned long)(ok)); Serial.print(")"); Serial.println();
    delay(2000);
}
