#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "DS3231.h"

// Backup-clock module for a data logger: reseeds the clock after a power
// loss, then logs a "reading" on every once-per-minute and once-per-hour
// alarm match, using the on-chip temperature sensor as the payload.

static volatile bool alarm1Fired = false, alarm2Fired = false;

static void onAlarm(uint8_t status) {
    if (status & DS3231Full::SOURCE_ALARM1) alarm1Fired = true;
    if (status & DS3231Full::SOURCE_ALARM2) alarm2Fired = true;
}

int main(void) {
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, DS3231Minimal::I2C_ADDRESS);
    DS3231Full rtc(connection);
    stdio_init_all();

    // --- Detect a lost power reference and reseed if needed ---
    if (rtc.oscillatorStopped()) {
        DS3231Minimal::DateTime reference{2026, 1, 1, 4, 0, 0, 0};
        rtc.setDatetime(reference);  // also clears the Oscillator Stop Flag
        printf("oscillator was stopped - reseeded from reference timestamp\n");
    }

    // --- Arm a once-per-minute log tick and a once-per-hour summary tick ---
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
                printf("[minute] %02u:%02u:%02u  %.2f C\n", dt.hour, dt.minute, dt.second, (double)tempC);
                logged++;
            }
            if (alarm2Fired) {
                printf("[hourly] %02u:%02u  %.2f C\n", dt.hour, dt.minute, (double)tempC);
            }
            alarm1Fired = alarm2Fired = false;
        }
        sleep_ms(200);
    }

    rtc.disableInterrupt(DS3231Full::SOURCE_ALARM1 | DS3231Full::SOURCE_ALARM2);
    rtc.offInterrupt();
    return 0;
}
