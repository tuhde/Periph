#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x18);

void setup() {
    Serial.begin(115200);
    Wire.begin();
    ES8311Minimal codec(connection);  // Create ES8311 driver, (connection, mclk_hz=12288000 Hz, sample_rate_hz=48000 Hz)
    codec.set_mic_gain(24.0f);                                  // Set microphone PGA gain, (db 0–30 dB, 3 dB steps) → void
    codec.set_mute(false);                                      // Unmute the DAC, (muted=false) → void
    codec.set_volume(-20.0f);                                   // Set DAC volume, (db −95.5–32 dB, 0.5 dB steps) → void
    delay(5000);
    codec.set_mute(true);                                       // Mute the DAC, (muted=true) → void
}

void loop() {
    delay(1000);
}
