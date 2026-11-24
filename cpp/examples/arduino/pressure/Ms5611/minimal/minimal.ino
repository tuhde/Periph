#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x77);
MS5611Minimal sensor(connection);                               // Create MS5611 driver, (connection) → void

void setup() {
    Serial.begin(115200);
    Wire.begin();
    sensor.init();                                                  // Reset, read PROM, verify CRC-4, () → bool
}

void loop() {
    float t = 0, p = 0;
    bool ok = sensor.read(t, p);                                    // Read temperature and pressure, (temperature_c&, pressure_pa&) → bool
    Serial.print("temperature "); Serial.print(t, 2); Serial.print(" C pressure "); Serial.print(p, 1); Serial.print(" Pa (ok="); Serial.print((unsigned long)(ok)); Serial.print(")"); Serial.println();
    delay(1000);
}
