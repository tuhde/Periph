#include <Wire.h>
#include <Periph.h>
#include <math.h>

I2CConnection connection(Wire, 0x77);
SPL06Minimal sensor(connection);                                   // Create SPL06 driver, (connection) → void

void setup() {
    Serial.begin(115200);
    Wire.begin();
    sensor.init();                                                 // Check ID, read coefficients, apply defaults, () → bool
}

void loop() {
    float t = 0, p = 0;
    bool ok = sensor.read(t, p);                                   // Measure temperature and pressure, (temperature_c&, pressure_pa&) → bool
    Serial.print("pressure "); Serial.print(p, 2); Serial.print(" Pa temperature "); Serial.print(t, 2); Serial.print(" C (ok="); Serial.print((unsigned long)(ok)); Serial.print(")"); Serial.println();
    delay(1000);
}
