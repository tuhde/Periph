#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x5F);
HTS221Minimal sensor(connection);                                   // Create HTS221 driver, (connection) → void

void setup() {
    Serial.begin(115200);
    Wire.begin();
    sensor.init();                                                  // Read calibration and start one-shot mode, () → bool
}

void loop() {
    float t = 0, rh = 0;
    bool ok = sensor.read(t, rh);                                        // Read temperature and humidity, (temperature_c, humidity_pct) → bool
    Serial.print("temperature "); Serial.print(t, 2); Serial.print(" C humidity "); Serial.print(rh, 1); Serial.print(" %rH (ok="); Serial.print((unsigned long)(ok)); Serial.print(")"); Serial.println();
    delay(2000);
}
