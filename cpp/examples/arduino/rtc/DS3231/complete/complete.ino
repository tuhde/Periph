#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, DS3231Minimal::I2C_ADDRESS);
DS3231Full rtc(connection);                              // Create DS3231 Full driver, (connection)

void onAlarm(uint8_t status) {
    Serial.print("alarm status=0x"); Serial.println(status, HEX);
}

void setup() {
    Serial.begin(115200);
    Wire.begin();

    DS3231Minimal::DateTime dt{2026, 9, 22, 2, 14, 30, 0};
    rtc.setDatetime(dt);                                  // Write calendar clock, (year, month, day, weekday, hour, minute, second) → None
                                                            // also forces 24-hour mode and clears the Oscillator Stop Flag
    rtc.getDatetime(dt);                                  // Read calendar clock, () → DateTime
                                                            // decodes the seven BCD clock/calendar registers
    float tempC = rtc.readTemperature();                  // Read last temperature conversion, () → float C
                                                            // no forced conversion; may be up to 64 s stale

    rtc.forceTemperatureConversion();                     // Force a fresh conversion, () → None
                                                            // sets CONV and blocks until BSY clears (max 200 ms)

    DS3231Full::Alarm1 a1{0, 30, 9, 0, false, DS3231Full::ALARM1_MATCH_HOURS_MINUTES_SECONDS};
    rtc.setAlarm1(a1);                                    // Configure Alarm 1, (second, minute, hour, dayOrDate, isDayOfWeek, matchMode) → None
                                                            // fires when hours, minutes, seconds all match
    DS3231Full::Alarm1 got1;
    rtc.getAlarm1(got1);                                  // Read Alarm 1 configuration, () → Alarm1
                                                            // decodes the mask bits back into matchMode

    DS3231Full::Alarm2 a2{0, 0, 0, false, DS3231Full::ALARM2_EVERY_MINUTE};
    rtc.setAlarm2(a2);                                    // Configure Alarm 2, (minute, hour, dayOrDate, isDayOfWeek, matchMode) → None
                                                            // fires once per minute at :00 seconds
    DS3231Full::Alarm2 got2;
    rtc.getAlarm2(got2);                                  // Read Alarm 2 configuration, () → Alarm2

    rtc.onInterrupt(onAlarm);                              // Subscribe to alarm interrupts, (callback, intPin=nullptr) → None
                                                            // sets INTCN=1 so INT/SQW carries alarm interrupts
    rtc.enableInterrupt(DS3231Full::SOURCE_ALARM1 | DS3231Full::SOURCE_ALARM2);  // Enable alarm sources, (source) → None
    uint8_t status = rtc.pollInterrupt();                 // Poll & clear alarm flags, () → uint8_t
                                                            // clears A1F/A2F only, leaves OSF/EN32kHz/BSY untouched
    rtc.disableInterrupt(DS3231Full::SOURCE_ALARM1);      // Disable one alarm source, (source) → None
    rtc.offInterrupt();                                   // Unsubscribe, () → None

    rtc.enableSquareWave(8192, false);                    // Enable square wave, (rateHz=8192, batteryBacked=false) → None
                                                            // sets INTCN=0 - mutually exclusive with alarm interrupts
    rtc.disableSquareWave();                              // Return INT/SQW to interrupt mode, () → None

    rtc.enable32kHzOutput();                              // Enable 32kHz output, () → None
    bool en32 = rtc.is32kHzEnabled();                     // Query 32kHz output, () → bool
    rtc.disable32kHzOutput();                             // Disable 32kHz output, () → None

    bool stopped = rtc.oscillatorStopped();               // Query Oscillator Stop Flag, () → bool
                                                            // true means timekeeping data may be invalid
    rtc.clearOscillatorStopped();                          // Clear Oscillator Stop Flag, () → None

    rtc.enableBatteryOscillator();                        // Keep oscillator running on VBAT, () → None
    rtc.disableBatteryOscillator();                       // Stop oscillator on VBAT, () → None
                                                            // saves battery current between power cycles

    rtc.setAgingOffset(-5);                               // Write oscillator trim code, (offset) → None
    int8_t aging = rtc.getAgingOffset();                  // Read oscillator trim code, () → int8_t
                                                            // raw signed trim value, no fixed physical scale

    Serial.print(dt.year); Serial.print('-'); Serial.print(dt.month); Serial.print('-'); Serial.println(dt.day);
    Serial.print(tempC); Serial.println(" C");
    Serial.print("alarm1 hour="); Serial.println(got1.hour);
    Serial.print("alarm2 minute="); Serial.println(got2.minute);
    Serial.print("status=0x"); Serial.println(status, HEX);
    Serial.print("32khz="); Serial.println(en32);
    Serial.print("osf="); Serial.println(stopped);
    Serial.print("aging="); Serial.println(aging);
}

void loop() {}
