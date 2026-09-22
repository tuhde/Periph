#include <Wire.h>
#include "I2CConnection.h"
#include "DS3231.h"

// Backup-clock module for a data logger: reseeds the clock after a power
// loss, then logs a "reading" on every once-per-minute and once-per-hour
// alarm match, using the on-chip temperature sensor as the payload.

I2CConnection connection(Wire, DS3231Minimal::I2C_ADDRESS);
DS3231Full rtc(connection);

volatile bool alarm1Fired = false, alarm2Fired = false;

void onAlarm(uint8_t status) {
    if (status & DS3231Full::SOURCE_ALARM1) alarm1Fired = true;
    if (status & DS3231Full::SOURCE_ALARM2) alarm2Fired = true;
}

void setup() {
    Serial.begin(115200);
    Wire.begin();

    // --- Detect a lost power reference and reseed if needed ---
    // A fresh chip, or one whose backup coin cell died, reports the
    // Oscillator Stop Flag set - its clock/calendar registers cannot be
    // trusted until reseeded from a known-good reference.
    if (rtc.oscillatorStopped()) {
        DS3231Minimal::DateTime reference{2026, 1, 1, 4, 0, 0, 0};
        rtc.setDatetime(reference);  // also clears the Oscillator Stop Flag
        Serial.println("oscillator was stopped - reseeded from reference timestamp");
    }

    // --- Arm a once-per-minute log tick and a once-per-hour summary tick ---
    // Both alarms repeat automatically (their registers are never
    // rewritten), so once armed, no further configuration is needed
    // between matches.
    DS3231Full::Alarm1 perMinute{0, 0, 0, 0, false, DS3231Full::ALARM1_MATCH_SECONDS};
    rtc.setAlarm1(perMinute);  // fires once per minute at :00 seconds
    DS3231Full::Alarm2 hourly{0, 0, 0, false, DS3231Full::ALARM2_MATCH_MINUTES};
    rtc.setAlarm2(hourly);     // fires once per hour at :00 minutes

    rtc.onInterrupt(onAlarm);
    rtc.enableInterrupt(DS3231Full::SOURCE_ALARM1 | DS3231Full::SOURCE_ALARM2);
}

void loop() {
    // --- Log every match; readings pair a timestamp with a temperature ---
    if (alarm1Fired || alarm2Fired) {
        DS3231Minimal::DateTime dt;
        rtc.getDatetime(dt);
        float tempC = rtc.readTemperature();
        if (alarm1Fired) {
            Serial.print("[minute] "); Serial.print(dt.hour); Serial.print(':');
            Serial.print(dt.minute); Serial.print(':'); Serial.print(dt.second);
            Serial.print("  "); Serial.print(tempC); Serial.println(" C");
            alarm1Fired = false;
        }
        if (alarm2Fired) {
            Serial.print("[hourly] "); Serial.print(dt.hour); Serial.print(':');
            Serial.print(dt.minute); Serial.print("  "); Serial.print(tempC); Serial.println(" C");
            alarm2Fired = false;
        }
    }
    delay(200);
}
