#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "DS3231.h"

#define I2C_NODE DT_NODELABEL(i2c0)

static void onAlarm(uint8_t status) {
    printk("alarm status=0x%02X\n", status);
}

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, DS3231Minimal::I2C_ADDRESS);
    DS3231Full rtc(connection);                             // Create DS3231 Full driver, (connection)

    DS3231Minimal::DateTime dt{2026, 9, 22, 2, 14, 30, 0};
    rtc.setDatetime(dt);                                    // Write calendar clock, (year, month, day, weekday, hour, minute, second) → None
                                                              // also forces 24-hour mode and clears the Oscillator Stop Flag
    rtc.getDatetime(dt);                                    // Read calendar clock, () → DateTime
    float tempC = rtc.readTemperature();                    // Read last temperature conversion, () → float C
                                                              // no forced conversion; may be up to 64 s stale

    rtc.forceTemperatureConversion();                       // Force a fresh conversion, () → None
                                                              // sets CONV and blocks until BSY clears (max 200 ms)

    DS3231Full::Alarm1 a1{0, 30, 9, 0, false, DS3231Full::ALARM1_MATCH_HOURS_MINUTES_SECONDS};
    rtc.setAlarm1(a1);                                      // Configure Alarm 1, (second, minute, hour, dayOrDate, isDayOfWeek, matchMode) → None
    DS3231Full::Alarm1 got1;
    rtc.getAlarm1(got1);                                    // Read Alarm 1 configuration, () → Alarm1

    DS3231Full::Alarm2 a2{0, 0, 0, false, DS3231Full::ALARM2_EVERY_MINUTE};
    rtc.setAlarm2(a2);                                      // Configure Alarm 2, (minute, hour, dayOrDate, isDayOfWeek, matchMode) → None
    DS3231Full::Alarm2 got2;
    rtc.getAlarm2(got2);                                    // Read Alarm 2 configuration, () → Alarm2

    rtc.onInterrupt(onAlarm);                                // Subscribe to alarm interrupts, (callback, intPin=nullptr) → None
    rtc.enableInterrupt(DS3231Full::SOURCE_ALARM1 | DS3231Full::SOURCE_ALARM2);  // Enable alarm sources, (source) → None
    uint8_t status = rtc.pollInterrupt();                   // Poll & clear alarm flags, () → uint8_t
    rtc.disableInterrupt(DS3231Full::SOURCE_ALARM1);        // Disable one alarm source, (source) → None
    rtc.offInterrupt();                                     // Unsubscribe, () → None

    rtc.enableSquareWave(8192, false);                      // Enable square wave, (rateHz=8192, batteryBacked=false) → None
    rtc.disableSquareWave();                                // Return INT/SQW to interrupt mode, () → None

    rtc.enable32kHzOutput();                                // Enable 32kHz output, () → None
    bool en32 = rtc.is32kHzEnabled();                       // Query 32kHz output, () → bool
    rtc.disable32kHzOutput();                               // Disable 32kHz output, () → None

    bool stopped = rtc.oscillatorStopped();                 // Query Oscillator Stop Flag, () → bool
    rtc.clearOscillatorStopped();                            // Clear Oscillator Stop Flag, () → None

    rtc.enableBatteryOscillator();                          // Keep oscillator running on VBAT, () → None
    rtc.disableBatteryOscillator();                         // Stop oscillator on VBAT, () → None

    rtc.setAgingOffset(-5);                                 // Write oscillator trim code, (offset) → None
    int8_t aging = rtc.getAgingOffset();                    // Read oscillator trim code, () → int8_t

    printk("%04u-%02u-%02u  %.2f C  alarm1h=%u alarm2m=%u  status=0x%02X  32khz=%d  osf=%d  aging=%d\n",
           dt.year, dt.month, dt.day, (double)tempC, got1.hour, got2.minute, status, en32, stopped, aging);
    return 0;
}
