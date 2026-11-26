#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x55);
Si1133Minimal sensor(connection);                                  // Create Si1133 driver, (connection) → void

void setup() {
    Serial.begin(115200);
    Wire.begin();
    sensor.init();                                                 // Check PART_ID, reset, UV + white channels, () → bool
}

void loop() {
    int32_t uv = 0, white = 0;
    bool ok = sensor.read(uv, white);                              // Forced measurement, (uv&, white&) → bool
    Serial.print("UV "); Serial.print((long)(uv)); Serial.print(" white "); Serial.print((long)(white)); Serial.print(" (ok="); Serial.print((unsigned)(ok)); Serial.print(")"); Serial.println();
    delay(1000);
}
