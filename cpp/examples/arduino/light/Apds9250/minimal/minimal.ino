#include <Wire.h>
#include <Periph.h>
#include <stdio.h>

I2CConnection connection(Wire, Apds9250Minimal::I2C_ADDRESS);
Apds9250Minimal sensor(connection);                             // Create APDS-9250 driver, (connection)

void setup() {
    Wire.begin();
    Serial.begin(115200);
    for (int i = 0; i < 10; i++) {
        Apds9250Counts c = {0, 0, 0, 0};
        sensor.read(c);                                             // Read the four channels, (counts&) → bool
        Serial.print("R "); Serial.print((unsigned long)(c.red)); Serial.print(" G "); Serial.print((unsigned long)(c.green)); Serial.print(" B "); Serial.print((unsigned long)(c.blue)); Serial.print(" IR "); Serial.print((unsigned long)(c.ir)); Serial.println();
        delay(1000);
    }
}

void loop() {
}
