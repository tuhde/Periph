#include <Wire.h>
#include <Periph.h>
#include <stdio.h>

I2CConnection connection(Wire, DS1881Minimal::I2C_ADDRESS);
DS1881Minimal amp(connection);                          // Create DS1881 driver, (connection)

void setup() {
    Wire.begin();
    Serial.begin(115200);
    amp.init();                                         // Apply defaults, () → void
    static const int dbs[] = {0, 10, 20, 40};
    for (int i = 0; i < 4; i++) {
        amp.setAttenuation(0, (float)dbs[i]);           // Set attenuation, (channel 0–1, db dB) → void
        amp.setAttenuation(1, (float)dbs[i]);           // Set attenuation, (channel 0–1, db dB) → void
        float a = amp.getAttenuation(0);                // Read attenuation, (channel 0–1) → float dB
        { char b[96]; snprintf(b, sizeof b, "attenuation %d dB, read back %d dB\n", dbs[i], (int)a); Serial.print(b); }
        delay(500);
    }
    amp.mute(0);                                        // Mute channel, (channel 0–1) → void
}

void loop() {
}
