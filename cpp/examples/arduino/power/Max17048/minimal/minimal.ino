#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x36);
Max17048Minimal gauge(connection, 1);   // Create MAX17048 driver, (connection, cells)

void setup() {
    Serial.begin(115200);
    Wire.begin();
}

void loop() {
    Serial.print(gauge.voltage(), 3); Serial.print(" V  "); Serial.print(gauge.percent(), 1); Serial.print(" "); Serial.println("%");  // Read V / %, () → float each
    delay(1000);
}
