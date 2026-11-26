#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x5A);
CCS811Minimal sensor(connection);                                  // Create CCS811 driver, (connection) → void

void setup() {
    Serial.begin(115200);
    Wire.begin();
    sensor.init();                                                 // Check HW_ID, start application, mode 1, () → bool
}

void loop() {
    uint16_t eco2 = 0, tvoc = 0;
    bool ok = sensor.read(eco2, tvoc);                             // Wait for the next sample, (eco2_ppm&, tvoc_ppb&) → bool
    Serial.print("eCO2 "); Serial.print((unsigned)(eco2)); Serial.print(" ppm TVOC "); Serial.print((unsigned)(tvoc)); Serial.print(" ppb (ok="); Serial.print((unsigned)(ok)); Serial.print(")"); Serial.println();
}
