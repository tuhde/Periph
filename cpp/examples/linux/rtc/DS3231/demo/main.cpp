#include <cstdio>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "DS3231.h"

// Backup-clock module for a data logger: reseeds the clock after a power
// loss, then logs a "reading" on every once-per-minute and once-per-hour
// alarm match, using the on-chip temperature sensor as the payload.

static volatile bool alarm1Fired = false, alarm2Fired = false;
static uint8_t lastStatus = 0;

static void onAlarm(uint8_t status) {
    lastStatus = status;
    if (status & DS3231Full::SOURCE_ALARM1) alarm1Fired = true;
    if (status & DS3231Full::SOURCE_ALARM2) alarm2Fired = true;
}

int main() {
    I2CConnectionLinux connection(1, DS3231Minimal::I2C_ADDRESS);
    DS3231Full rtc(connection);

    // --- Detect a lost power reference and reseed if needed ---
    // A fresh chip, or one whose backup coin cell died, reports the
    // Oscillator Stop Flag set — its clock/calendar registers cannot be
    // trusted until reseeded from a known-good reference.
    if (rtc.oscillatorStopped()) {
        DS3231Minimal::DateTime reference{2026, 1, 1, 4, 0, 0, 0};
        rtc.setDatetime(reference);  // also clears the Oscillator Stop Flag
        printf("oscillator was stopped - reseeded from reference timestamp\n");
    }

    // --- Arm a once-per-minute log tick and a once-per-hour summary tick ---
    // Both alarms repeat automatically (their registers are never rewritten),
    // so once armed, no further configuration is needed between matches.
    DS3231Full::Alarm2 hourlyTick{0, 0, 0, false, DS3231Full::ALARM2_MATCH_MINUTES};
    DS3231Full::Alarm1 perMinuteViaAlarm1{0, 0, 0, 0, false, DS3231Full::ALARM1_MATCH_SECONDS};
    rtc.setAlarm1(perMinuteViaAlarm1);   // fires once per minute at :00 seconds
    rtc.setAlarm2(hourlyTick);           // fires once per hour at :00 minutes

    rtc.onInterrupt(onAlarm);
    rtc.enableInterrupt(DS3231Full::SOURCE_ALARM1 | DS3231Full::SOURCE_ALARM2);

    // --- Log every match for a fixed number of minute-ticks, then clean up ---
    int logged = 0;
    while (logged < 5) {
        if (alarm1Fired || alarm2Fired) {
            DS3231Minimal::DateTime dt;
            rtc.getDatetime(dt);
            float tempC = rtc.readTemperature();
            if (alarm1Fired) {
                printf("[minute] %04u-%02u-%02u %02u:%02u:%02u  %.2f C  (status=0x%02X)\n",
                       dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second,
                       (double)tempC, lastStatus);
                logged++;
            }
            if (alarm2Fired) {
                printf("[hourly] %04u-%02u-%02u %02u:%02u:%02u  %.2f C  (status=0x%02X)\n",
                       dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second,
                       (double)tempC, lastStatus);
            }
            alarm1Fired = alarm2Fired = false;
        }
        usleep(200000);
    }

    rtc.disableInterrupt(DS3231Full::SOURCE_ALARM1 | DS3231Full::SOURCE_ALARM2);
    rtc.offInterrupt();
    return 0;
}
