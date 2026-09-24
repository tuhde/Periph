///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

// Scheduling core of a battery-backed logger: reseeds the clock after a
// power loss, checks the coin cell, then wakes on an hourly alarm to print
// a timestamp while Timer B pulses a 30-second "still running" heartbeat
// on INT2 that toggles an LED.

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.rtc.PCF8523Full;
import it.uhde.periph.chips.rtc.PCF8523Full.Alarm;
import it.uhde.periph.chips.rtc.PCF8523Full.BatteryMode;
import it.uhde.periph.chips.rtc.PCF8523Full.SourceClock;

import java.util.concurrent.CountDownLatch;

public class Demo {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, PCF8523Full.DEFAULT_ADDRESS)) {     // open I²C bus, fixed device address, (bus, address) → I2CConnection
            var rtc = new PCF8523Full(connection);                                         // Create PCF8523 Full driver, (connection) → PCF8523Full

            // --- Detect a lost time reference and reseed if needed ---
            // A fresh chip, or one whose backup cell was disconnected too long,
            // reports the OS flag set: its calendar cannot be trusted until reseeded.
            if (rtc.oscillatorStopped()) {                                                 // Query oscillator-stop flag, () → boolean
                rtc.setDatetime(2026, 1, 1, 4, 0, 0, 0);                                   // Set calendar clock, (year, month, day, weekday 0=Sun, hour, minute, second) → void
                System.out.println("oscillator was stopped - reseeded from reference timestamp");
            }

            // --- Keep the clock alive through power cuts ---
            // Standard switch-over is already the driver default; it is repeated
            // here so the logger's power policy is explicit. A low coin cell is
            // reported once so it can be replaced before the next outage.
            rtc.configureBatteryBackup(BatteryMode.STANDARD);                              // Select battery switch-over, (mode, lowDetection=true) → void
            if (rtc.isBatteryLow()) {                                                      // Query battery-low flag, () → boolean
                System.out.println("warning: backup battery low - replace the coin cell");
            }

            // --- Hourly wake-up plus a 30 s heartbeat ---
            // Only the minute field is enabled, so the alarm matches at hh:00 every
            // hour. Timer B reloads automatically and has its own INT2 pin, so the
            // heartbeat keeps running independently of the hourly alarm.
            rtc.disableClockOutput();                                                      // Disable CLKOUT, () → void
            rtc.setAlarm(new Alarm(0, null, null, null));                                  // Configure alarm, (Alarm(minute, hour, day, weekday)) → void
            rtc.configureTimerB(30, SourceClock.HZ_1);                                     // Start Timer B, (value 0–255, sourceClock, pulseWidthMs=46.875 ms, pulsed=false) → void

            // --- Dispatch by source: log on the alarm, blink on the heartbeat ---
            var alarms = new CountDownLatch(3);
            boolean[] ledOn = {false};
            rtc.onInterrupt(status -> {                                                    // Subscribe to interrupts, (callback) → void
                try {
                    if ((status & PCF8523Full.SOURCE_ALARM) != 0) {
                        var dt = rtc.getDatetime();                                        // Read calendar clock, () → DateTime
                        System.out.printf("[hourly] %04d-%02d-%02d %02d:%02d:%02d%n",
                                dt.year(), dt.month(), dt.day(), dt.hour(), dt.minute(), dt.second());
                        alarms.countDown();
                    }
                    if ((status & PCF8523Full.SOURCE_TIMER_B) != 0) {
                        ledOn[0] = !ledOn[0];
                        System.out.println("[heartbeat] LED " + (ledOn[0] ? "on" : "off"));
                    }
                } catch (java.io.IOException e) {
                    System.err.println("bus error: " + e.getMessage());
                }
            });
            rtc.enableInterrupt(PCF8523Full.SOURCE_ALARM | PCF8523Full.SOURCE_TIMER_B);   // Enable sources, (source) → void
            alarms.await();

            // --- Leave the chip quiet on exit ---
            rtc.disableInterrupt(PCF8523Full.SOURCE_ALARM | PCF8523Full.SOURCE_TIMER_B);  // Disable sources, (source) → void
            rtc.disableTimerB();                                                           // Stop Timer B, () → void
            rtc.offInterrupt();                                                            // Unsubscribe, () → void
        }
    }
}
