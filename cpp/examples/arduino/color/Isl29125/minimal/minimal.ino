#include <Wire.h>
#include <Periph.h>
#include <stdio.h>

I2CConnection connection(Wire, Isl29125Minimal::I2C_ADDRESS);
Isl29125Minimal sensor(connection);                             // Create ISL29125 driver, (connection)

void setup() {
    Wire.begin();
    Serial.begin(115200);
    for (int i = 0; i < 10; i++) {
        Isl29125Counts c = {0, 0, 0};
        sensor.read(c);                                             // Read the three channels, (counts&) → bool
        Serial.print("R "); Serial.print((unsigned long)(c.red)); Serial.print(" G "); Serial.print((unsigned long)(c.green)); Serial.print(" B "); Serial.print((unsigned long)(c.blue)); Serial.println();
        delay(1000);
    }
}

void loop() {
}
