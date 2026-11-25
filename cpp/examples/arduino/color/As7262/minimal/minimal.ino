#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x49);
AS7262Minimal sensor(connection);                                   // Create AS7262 driver, (connection) → void

void setup() {
    Serial.begin(115200);
    Wire.begin();
    sensor.begin();                                                 // Reset and configure defaults, () → bool
}

void loop() {
    AS7262Channels ch = {};
    bool ok = sensor.read(ch);                                      // Read six calibrated channels, (ch) → bool
    Serial.print("V "); Serial.print(ch.violet, 1); Serial.print(" B "); Serial.print(ch.blue, 1); Serial.print(" G "); Serial.print(ch.green, 1); Serial.print(" Y "); Serial.print(ch.yellow, 1); Serial.print(" O "); Serial.print(ch.orange, 1); Serial.print(" R "); Serial.print(ch.red, 1); Serial.print(" (ok="); Serial.print((unsigned long)(ok)); Serial.print(")"); Serial.println();
    Serial.print(sensor.temperature(), 0); Serial.print(" C"); Serial.println();
    delay(2000);
}
