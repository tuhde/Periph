#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "DS3231.h"

// Backup-clock module for a data logger: reseeds the clock after a power
// loss, then logs a "reading" on every once-per-minute and once-per-hour
// alarm match, using the on-chip temperature sensor as the payload.

#define I2C_NODE DT_NODELABEL(i2c0)

static volatile bool alarm1Fired = false, alarm2Fired = false;

static void onAlarm(uint8_t status) {
    if (status & DS3231Full::SOURCE_ALARM1) alarm1Fired = true;
    if (status & DS3231Full::SOURCE_ALARM2) alarm2Fired = true;
}

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, DS3231Minimal::I2C_ADDRESS);
    DS3231Full rtc(connection);

    // --- Detect a lost power reference and reseed if needed ---
    // A fresh chip, or one whose backup coin cell died, reports the
    // Oscillator Stop Flag set - its clock/calendar registers cannot be
    // trusted until reseeded from a known-good reference.
    if (rtc.oscillatorStopped()) {
        DS3231Minimal::DateTime reference{2026, 1, 1, 4, 0, 0, 0};
        rtc.setDatetime(reference);  // also clears the Oscillator Stop Flag
        printk("oscillator was stopped - reseeded from reference timestamp\n");
    }

    // --- Arm a once-per-minute log tick and a once-per-hour summary tick ---
    // Both alarms repeat automatically (their registers are never
    // rewritten), so once armed, no further configuration is needed
    // between matches.
    DS3231Full::Alarm1 perMinute{0, 0, 0, 0, false, DS3231Full::ALARM1_MATCH_SECONDS};
    rtc.setAlarm1(perMinute);
    DS3231Full::Alarm2 hourly{0, 0, 0, false, DS3231Full::ALARM2_MATCH_MINUTES};
    rtc.setAlarm2(hourly);

    rtc.onInterrupt(onAlarm);
    rtc.enableInterrupt(DS3231Full::SOURCE_ALARM1 | DS3231Full::SOURCE_ALARM2);

    int logged = 0;
    while (logged < 5) {
        if (alarm1Fired || alarm2Fired) {
            DS3231Minimal::DateTime dt;
            rtc.getDatetime(dt);
            float tempC = rtc.readTemperature();
            if (alarm1Fired) {
                printk("[minute] %02u:%02u:%02u  %.2f C\n", dt.hour, dt.minute, dt.second, (double)tempC);
                logged++;
            }
            if (alarm2Fired) {
                printk("[hourly] %02u:%02u  %.2f C\n", dt.hour, dt.minute, (double)tempC);
            }
            alarm1Fired = alarm2Fired = false;
        }
        k_sleep(K_MSEC(200));
    }

    rtc.disableInterrupt(DS3231Full::SOURCE_ALARM1 | DS3231Full::SOURCE_ALARM2);
    rtc.offInterrupt();
    return 0;
}
