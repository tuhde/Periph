#include <Wire.h>
#include <Periph.h>
#include <stdio.h>

I2CConnection connection(Wire, DS1881Full::I2C_ADDRESS);
DS1881Full amp(connection);                             // Create DS1881 Full driver, (connection)

void setup() {
    Wire.begin();
    Serial.begin(115200);
    amp.init();                                         // Apply defaults, () → void

    // --- Fade the volume down and back up ---
    // Zero-crossing detection (on by default) moves the wiper only at a signal
    // zero crossing, so each 2 dB step is clickless; 100 ms per step is longer
    // than the 50 ms maximum zero-crossing window.
    for (int step = 0; step < 42; step++) {
        int db = step <= 20 ? step * 2 : (41 - step) * 2;
        amp.setAttenuationBoth((float)db);              // Set both attenuations, (db0 dB, db1=db0 dB) → void
        uint8_t p0, p1, cfgByte;
        amp.readAll(p0, p1, cfgByte);                   // Read everything, (&position0, &position1, &config) → void
        { char b[96]; snprintf(b, sizeof b, "%2d dB -> positions %d %d\n", db, (int)p0, (int)p1); Serial.print(b); }
        delay(100);
    }

    // --- Mute and unmute one channel ---
    // Muting channel 0 leaves channel 1 playing; restoring writes the position again.
    amp.mute(0);                                        // Mute channel, (channel 0–1) → void
    { char b[96]; snprintf(b, sizeof b, "channel 0 muted: %d dB\n", (int)amp.getAttenuation(0)); Serial.print(b); } // Read attenuation, (channel 0–1) → float dB
    delay(1000);
    amp.setAttenuation(0, 0.0f);                        // Set attenuation, (channel 0–1, db dB) → void

    // --- Inspect the Option 2 attenuation table ---
    // Option 2 (DS1808 compatible) uses 1, 2 and 3 dB steps; positions keep their
    // numeric value when the option changes, so re-set them afterwards.
    amp.setOption(2);                                   // Select option, (option 1–2) → void
    static const int table[] = {0, 12, 13, 24, 25, 32, 33};
    for (int i = 0; i < 7; i++) {
        { char b[96]; snprintf(b, sizeof b, "position %d -> %d dB\n", table[i], (int)amp.positionToDb(table[i])); Serial.print(b); } // Table lookup, (position) → float dB
    }
    amp.setOption(1);                                   // Select option, (option 1–2) → void
    amp.setAttenuationBoth(10.0f);                      // Set both attenuations, (db0 dB, db1=db0 dB) → void
    DS1881Config cfg = amp.getConfig();                 // Read configuration, () → DS1881Config {option, zeroCrossing, nonvolatile, raw}
    { char b[96]; snprintf(b, sizeof b, "option %d zc %d nv %d\n", (int)cfg.option, (int)cfg.zeroCrossing, (int)cfg.nonvolatile); Serial.print(b); }

    // --- Optionally persist the volume ---
    // Stored positions are restored at power-up. The EEPROM is rated for only
    // 50 000 writes, so this runs only when requested (--store on Linux).
    if (false) {
        { char b[96]; snprintf(b, sizeof b, "WARNING: EEPROM endurance is 50 000 writes\n"); Serial.print(b); }
        amp.setNonvolatile(true);                       // Select EEPROM storage, (enabled bool) → void
        amp.store();                                    // Store positions, () → void
        { char b[96]; snprintf(b, sizeof b, "store acknowledged: %d\n", (int)amp.waitReady(0.05f)); Serial.print(b); } // Acknowledge-poll, (timeoutS=0.05 s) → bool
        amp.setNonvolatile(false);                      // Select EEPROM storage, (enabled bool) → void
    }
}

void loop() {
}
