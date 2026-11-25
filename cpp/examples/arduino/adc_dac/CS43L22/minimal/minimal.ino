#include <Wire.h>
#include <Periph.h>
#include <stdio.h>

I2CConnection connection(Wire, CS43L22Minimal::I2C_ADDRESS);
CS43L22Minimal dac(connection);                      // Create CS43L22 driver, (connection, resPin=nullptr)

void setup() {
    Wire.begin();
    Serial.begin(115200);
    dac.init();                                      // Reset, verify ID, load defaults, () → bool
    dac.start();                                     // Power up the DAC once MCLK is running, () → void
    dac.setVolume(-20.0f);                           // Set master volume, (db -102–12 dB) → void
    dac.mute(false);                                 // Unmute the headphone outputs, (on=true) → void
    for (int i = 0; i < 5; i++) {
        float v = dac.getVolume();                   // Read back master volume, () → float dB
        { char b[96]; snprintf(b, sizeof b, "volume %d dB\n", (int)v); Serial.print(b); }
        delay(1000);
    }
    dac.stop();                                      // Power down the DAC, () → void
}

void loop() {
}
