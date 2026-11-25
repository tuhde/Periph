#include <stdarg.h>
#include <Wire.h>
#include <Periph.h>

static void logPrintf(const char* fmt, ...) {
    char buf[128];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    Serial.print(buf);
}

// Scheduling core of a battery-powered logger: reseeds the clock after a
// power loss, counts boots in the free RAM byte, then wakes on an hourly
// alarm to print a timestamp while a 30-second pulsed countdown timer
// toggles an LED as a "still running" heartbeat.

static volatile uint8_t pendingStatus = 0;
static bool ledOn = false;

static void onEvent(uint8_t status) {
    pendingStatus |= status;
}

void setup() {
    Serial.begin(115200);
    Wire.begin();
    I2CConnection connection(Wire, PCF85063AMinimal::I2C_ADDRESS);

    pinMode(LED_BUILTIN, OUTPUT);
    PCF85063AFull rtc(connection);                           // Create PCF85063A Full driver, (connection)

    // --- Detect a lost time reference and reseed if needed ---
    // A fresh chip, or one whose supply dropped out, reports the OS flag set:
    // its calendar cannot be trusted until it is reseeded. The crystal needs
    // up to 2 s to start, so wait until the flag stays clear.
    if (rtc.oscillatorStopped()) {                           // Query oscillator-stop flag, () → bool
        PCF85063AMinimal::DateTime reference{2026, 1, 1, 4, 0, 0, 0};
        rtc.setDatetime(reference);                          // Write calendar clock, (year, month, day, weekday 0=Sun, hour, minute, second) → bool
        logPrintf("oscillator was stopped - reseeded from reference timestamp\n");
        while (rtc.oscillatorStopped()) {                    // Query oscillator-stop flag, () → bool
            delay(100);
        }
    }

    // --- Count boots in the free RAM byte ---
    // The RAM byte survives as long as VDD is present, so it works as a tiny
    // non-volatile counter next to the clock.
    uint8_t boots = (uint8_t)(rtc.readRam() + 1);            // Read RAM byte, () → uint8_t
    rtc.writeRam(boots);                                     // Write RAM byte, (value 0–255) → void
    PCF85063AMinimal::DateTime now;
    rtc.getDatetime(now);                                    // Read calendar clock, () → DateTime
    int8_t offset;
    PCF85063AFull::OffsetMode offsetMode;
    rtc.getOffset(offset, offsetMode);                       // Read offset calibration, () → (int8_t, OffsetMode)
    logPrintf("boot #%u %04u-%02u-%02u %02u:%02u:%02u offset=%d\n",
        boots, now.year, now.month, now.day, now.hour, now.minute, now.second, offset);

    // --- Tune the board for low power ---
    // Match the crystal's load capacitance and switch the 32.768 kHz CLKOUT
    // off — it is enabled at power-on and costs current on a battery device.
    rtc.setOscillatorLoad(12.5f);                            // Select crystal load, (picofarads 7|12.5) → void
    rtc.disableClockOutput();                                // Disable CLKOUT, () → void

    // --- Hourly wake-up plus a 30 s heartbeat ---
    // Only second and minute are enabled, so the alarm matches at hh:00:00
    // every hour. The pulsed timer reloads automatically; minute ticks share
    // the same TF flag and are reported as SOURCE_TIMER.
    PCF85063AFull::Alarm hourly{0, 0, PCF85063AFull::ALARM_DISABLED, PCF85063AFull::ALARM_DISABLED, PCF85063AFull::ALARM_DISABLED};
    rtc.setAlarm(hourly);                                    // Configure alarm, (second, minute, hour, day, weekday; ALARM_DISABLED = ignore) → void
    rtc.configureTimer(30, PCF85063AFull::SourceClock::Hz1, true);  // Start countdown timer, (value 1–255, sourceClock, pulsed=false) → void
    rtc.setMinuteInterrupt(PCF85063AFull::MinuteInterrupt::Minute);  // Select minute interrupt, (mode Off|Minute|HalfMinute) → void

    rtc.onInterrupt(onEvent);                                // Subscribe to interrupts, (callback, intPin=nullptr) → void
    rtc.enableInterrupt(PCF85063AFull::SOURCE_ALARM | PCF85063AFull::SOURCE_TIMER);  // Enable sources, (source) → void

    // --- Dispatch by source: log on the alarm, blink on the heartbeat ---
    // Without an INT pin on the connection, poll the flags instead.
    int alarms = 0;
    int ticks = 0;
    while (alarms < 5 && ticks < 5 * 60 * 5) {
        if (!connection.intPin()) pendingStatus |= rtc.pollInterrupt();  // Poll & clear flags, () → uint8_t
        uint8_t status = pendingStatus;
        pendingStatus = 0;
        if (status & PCF85063AFull::SOURCE_ALARM) {
            PCF85063AMinimal::DateTime dt;
            rtc.getDatetime(dt);                             // Read calendar clock, () → DateTime
            logPrintf("[hourly] %04u-%02u-%02u %02u:%02u:%02u\n",
                dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second);
            alarms++;
        }
        if (status & PCF85063AFull::SOURCE_TIMER) {
            ledOn = !ledOn;
            digitalWrite(LED_BUILTIN, ledOn ? HIGH : LOW);
        }
        delay(200);
        ticks++;
    }

    // --- Leave the chip quiet on exit ---
    rtc.disableInterrupt(PCF85063AFull::SOURCE_ALARM | PCF85063AFull::SOURCE_TIMER);  // Disable sources, (source) → void
    rtc.disableTimer();                                      // Stop timer, () → void
    rtc.setMinuteInterrupt(PCF85063AFull::MinuteInterrupt::Off);  // Select minute interrupt, (mode Off|Minute|HalfMinute) → void
    rtc.offInterrupt();                                      // Unsubscribe, () → void
}

void loop() {}
