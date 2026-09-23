#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "PCF8523.h"

#define I2C_NODE DT_NODELABEL(i2c0)

// Scheduling core of a battery-backed logger: reseeds the clock after a
// power loss, checks the coin cell, then wakes on an hourly alarm to print
// a timestamp while Timer B pulses a 30-second "still running" heartbeat
// on INT2 that toggles an LED.

static volatile uint8_t pendingStatus = 0;
static bool ledOn = false;

static void onEvent(uint8_t status) {
    pendingStatus |= status;
}

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, PCF8523Minimal::I2C_ADDRESS);

    PCF8523Full rtc(connection);                             // Create PCF8523 Full driver, (connection)

    // --- Detect a lost time reference and reseed if needed ---
    // A fresh chip, or one whose backup cell was disconnected too long, reports
    // the OS flag set: its calendar cannot be trusted until it is reseeded.
    if (rtc.oscillatorStopped()) {                           // Query oscillator-stop flag, () → bool
        PCF8523Minimal::DateTime reference{2026, 1, 1, 4, 0, 0, 0};
        rtc.setDatetime(reference);                          // Write calendar clock, (year, month, day, weekday 0=Sun, hour, minute, second) → void
        printk("oscillator was stopped - reseeded from reference timestamp\n");
    }

    // --- Keep the clock alive through power cuts ---
    // Standard switch-over is already the driver default; it is repeated here
    // so the logger's power policy is explicit. A low coin cell is reported
    // once so it can be replaced before the next outage.
    rtc.configureBatteryBackup(PCF8523Full::BatteryMode::Standard);  // Select battery switch-over, (mode, lowDetection=true) → void
    if (rtc.isBatteryLow()) {                                // Query battery-low flag, () → bool
        printk("warning: backup battery low - replace the coin cell\n");
    }

    // --- Hourly wake-up plus a 30 s heartbeat ---
    // Only the minute field is enabled, so the alarm matches at hh:00 every
    // hour. Timer B reloads automatically and has its own INT2 pin, so the
    // heartbeat keeps running independently of the hourly alarm.
    rtc.disableClockOutput();                                // Disable CLKOUT, () → void
    PCF8523Full::Alarm hourly{0, PCF8523Full::ALARM_DISABLED, PCF8523Full::ALARM_DISABLED, PCF8523Full::ALARM_DISABLED};
    rtc.setAlarm(hourly);                                    // Configure alarm, (minute, hour, day, weekday; ALARM_DISABLED = ignore) → void
    rtc.configureTimerB(30, PCF8523Full::SourceClock::Hz1);  // Start Timer B, (value 0–255, sourceClock, pulseWidthMs=46.875 ms, pulsed=false) → void

    rtc.onInterrupt(onEvent);                                // Subscribe to interrupts, (callback, intPin=nullptr) → void
    rtc.enableInterrupt(PCF8523Full::SOURCE_ALARM | PCF8523Full::SOURCE_TIMER_B);  // Enable sources, (source) → void

    // --- Dispatch by source: log on the alarm, blink on the heartbeat ---
    // Without an INT pin on the connection, poll the flags instead.
    int alarms = 0;
    while (alarms < 3) {
        if (!connection.intPin()) pendingStatus |= rtc.pollInterrupt();  // Poll & clear flags, () → uint8_t
        uint8_t status = pendingStatus;
        pendingStatus = 0;
        if (status & PCF8523Full::SOURCE_ALARM) {
            PCF8523Minimal::DateTime dt;
            rtc.getDatetime(dt);                             // Read calendar clock, () → DateTime
            printk("[hourly] %04u-%02u-%02u %02u:%02u:%02u\n",
                dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second);
            alarms++;
        }
        if (status & PCF8523Full::SOURCE_TIMER_B) {
            ledOn = !ledOn;
            printk("[heartbeat] LED %s\n", ledOn ? "on" : "off");
        }
        k_sleep(K_MSEC(200));
    }

    // --- Leave the chip quiet on exit ---
    rtc.disableInterrupt(PCF8523Full::SOURCE_ALARM | PCF8523Full::SOURCE_TIMER_B);  // Disable sources, (source) → void
    rtc.disableTimerB();                                     // Stop Timer B, () → void
    rtc.offInterrupt();                                      // Unsubscribe, () → void
    return 0;
}
