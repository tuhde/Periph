#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x60);
MPL3115A2Minimal sensor(connection);                               // Create MPL3115A2 driver, (connection) → void

void setup() {
    Serial.begin(115200);
    Wire.begin();
    sensor.init();                                                 // Reset and apply defaults, () → bool
}

void loop() {
    float p = 0, t = 0;
    bool ok = sensor.read(p, t);                                   // Read pressure and temperature, (pressure_pa&, temperature_c&) → bool
    Serial.print("pressure "); Serial.print(p, 2); Serial.print(" Pa temperature "); Serial.print(t, 2); Serial.print(" C (ok="); Serial.print((unsigned long)(ok)); Serial.print(")"); Serial.println();
    delay(2000);
}
