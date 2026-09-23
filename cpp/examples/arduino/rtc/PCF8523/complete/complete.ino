#include <stdarg.h>
#include <Wire.h>
#include "I2CConnection.h"
#include "PCF8523.h"

static void logPrintf(const char* fmt, ...) {
    char buf[128];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    Serial.print(buf);
}

static void onEvent(uint8_t status) {
    logPrintf("interrupt status=0x%02X\n", status);
}

void setup() {
    Serial.begin(115200);
    Wire.begin();
    I2CConnection connection(Wire, PCF8523Minimal::I2C_ADDRESS);

    PCF8523Full rtc(connection);                             // Create PCF8523 Full driver, (connection)
                                                             // enables battery switch-over standard mode (PM=000)

    PCF8523Minimal::DateTime dt{2026, 9, 23, 3, 14, 30, 0};
    rtc.setDatetime(dt);                                     // Write calendar clock, (year, month, day, weekday 0=Sun, hour, minute, second) → void
                                                             // STOP-bit precision start; forces 24-hour mode and clears OS
    rtc.getDatetime(dt);                                     // Read calendar clock, () → DateTime
                                                             // decodes the seven BCD clock/calendar registers
    bool stopped = rtc.oscillatorStopped();                  // Query oscillator-stop flag, () → bool
                                                             // true means the time may be invalid until setDatetime

    PCF8523Full::Alarm alarm{0, 9, PCF8523Full::ALARM_DISABLED, PCF8523Full::ALARM_DISABLED};
    rtc.setAlarm(alarm);                                     // Configure alarm, (minute, hour, day, weekday; ALARM_DISABLED = ignore) → void
                                                             // fires daily at 09:00
    rtc.getAlarm(alarm);                                     // Read alarm, () → Alarm
                                                             // disabled fields decode as ALARM_DISABLED

    rtc.configureTimerA(PCF8523Full::TimerAMode::Countdown, 10, PCF8523Full::SourceClock::Hz1);  // Start Timer A, (mode, value 0–255, sourceClock, pulsed=false) → void
                                                             // counts down 10 s, then sets CTAF
    uint8_t remainingA = rtc.readTimerA();                   // Read Timer A counter, () → uint8_t
                                                             // live value, not the loaded one
    rtc.disableTimerA();                                     // Stop Timer A, () → void

    rtc.configureTimerB(30, PCF8523Full::SourceClock::Hz1, 62.5f, true);  // Start Timer B, (value 0–255, sourceClock, pulseWidthMs=46.875 ms, pulsed=false) → void
                                                             // 30 s countdown, pulsed 62.5 ms low on INT1 and INT2
    uint8_t remainingB = rtc.readTimerB();                   // Read Timer B counter, () → uint8_t
    rtc.disableTimerB();                                     // Stop Timer B, () → void

    rtc.setClockOutput(1);                                   // Drive CLKOUT, (frequencyHz) → void
                                                             // 1 Hz square wave on the shared INT1/CLKOUT pin
    rtc.disableClockOutput();                                // Disable CLKOUT, () → void
                                                             // frees INT1 for interrupts

    rtc.setOffset(-3, PCF8523Full::OffsetMode::EveryTwoHours);  // Write offset calibration, (offset −64–63, mode=EveryTwoHours) → void
                                                             // −3 LSB × 4.34 ppm = −13.02 ppm correction
    int8_t offset;
    PCF8523Full::OffsetMode offsetMode;
    rtc.getOffset(offset, offsetMode);                       // Read offset calibration, () → (int8_t, OffsetMode)

    rtc.configureBatteryBackup(PCF8523Full::BatteryMode::Standard, true);  // Select battery switch-over, (mode, lowDetection=true) → void
                                                             // switches to VBAT when VDD < VBAT and VDD < 2.5 V
    bool switched = rtc.isBatterySwitchedOver();             // Query switch-over flag, () → bool
    rtc.clearBatterySwitchover();                            // Clear switch-over flag, () → void
    bool low = rtc.isBatteryLow();                           // Query battery-low flag, () → bool
                                                             // read-only; clears itself once the cell is replaced

    rtc.onInterrupt(onEvent);                                // Subscribe to interrupts, (callback, intPin=nullptr) → void
                                                             // needs an INT pin on the connection or the intPin argument
    rtc.enableInterrupt(PCF8523Full::SOURCE_ALARM | PCF8523Full::SOURCE_TIMER_B | PCF8523Full::SOURCE_BATTERY_LOW);  // Enable sources, (source) → void
                                                             // sets AIE, CTBIE and BLIE
    uint8_t status = rtc.pollInterrupt();                    // Poll & clear flags, () → uint8_t
                                                             // clears CTAF/CTBF/SF/AF/BSF, returns the pre-clear mask
    rtc.disableInterrupt(PCF8523Full::SOURCE_ALARM | PCF8523Full::SOURCE_TIMER_A | PCF8523Full::SOURCE_TIMER_B | PCF8523Full::SOURCE_BATTERY_LOW);  // Disable sources, (source) → void
    rtc.offInterrupt();                                      // Unsubscribe, () → void

    rtc.softwareReset();                                     // Software reset, () → void
                                                             // control registers back to POR (PM=111); time is kept

    logPrintf("%04u-%02u-%02u %02u:%02u:%02u os_stopped=%d\n",
        dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second, stopped);
    logPrintf("alarm %u:%u timerA=%u timerB=%u\n", alarm.hour, alarm.minute, remainingA, remainingB);
    logPrintf("offset=%d every_minute=%d switched=%d low=%d status=0x%02X\n",
        offset, offsetMode == PCF8523Full::OffsetMode::EveryMinute, switched, low, status);
}

void loop() {}
