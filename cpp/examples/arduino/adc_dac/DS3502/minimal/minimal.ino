#include <Wire.h>
#include <Periph.h>
#include <stdio.h>

I2CConnection connection(Wire, DS3502Minimal::I2C_ADDRESS);
DS3502Minimal pot(connection, 15.0f, 0.0f);             // Create DS3502 driver, (connection, vHigh=1.0 V, vLow=0.0 V)

void setup() {
    Wire.begin();
    Serial.begin(115200);
    static const int codes[] = {0, 32, 64, 96, 127};
    for (int i = 0; i < 5; i++) {
        pot.setWiper(codes[i]);                         // Set wiper position, (code 0–127) → void
        uint8_t w = pot.getWiper();                     // Read wiper position, () → uint8_t 0–127
        float v = pot.getVoltage();                     // Read unloaded RW voltage, () → float V
        Serial.print("code "); Serial.print(codes[i]); Serial.print(" read "); Serial.print((int)w); Serial.print(" -> "); Serial.print((double)v, 3); Serial.println(" V");
        delay(500);
    }
    pot.setVoltage(6.0f);                               // Set RW output voltage, (volts V) → void
}

void loop() {
}
