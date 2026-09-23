///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.0
//DEPS it.uhde:periph-kotlin:1.2.0

// Scheduling core of a battery-backed logger: reseeds the clock after a
// power loss, checks the coin cell, then wakes on an hourly alarm to print
// a timestamp while Timer B pulses a 30-second "still running" heartbeat
// on INT2 that toggles an LED.

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.rtc.PCF8523Full
import it.uhde.periph.chips.rtc.PCF8523Minimal
import it.uhde.periph.chips.rtc.PCF8523Full.Alarm
import it.uhde.periph.chips.rtc.PCF8523Full.BatteryMode
import it.uhde.periph.chips.rtc.PCF8523Full.SourceClock
import java.util.concurrent.CountDownLatch

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, PCF8523Minimal.DEFAULT_ADDRESS).use { connection ->     // open I²C bus, fixed device address, (bus, address) → I2CConnection
        val rtc = PCF8523Full(connection)                                  // Create PCF8523 Full driver, (connection) → PCF8523Full

        // --- Detect a lost time reference and reseed if needed ---
        // A fresh chip, or one whose backup cell was disconnected too long,
        // reports the OS flag set: its calendar cannot be trusted until reseeded.
        if (rtc.oscillatorStopped()) {                                     // Query oscillator-stop flag, () → Boolean
            rtc.setDatetime(2026, 1, 1, 4, 0, 0, 0)                        // Set calendar clock, (year, month, day, weekday 0=Sun, hour, minute, second) → Unit
            println("oscillator was stopped - reseeded from reference timestamp")
        }

        // --- Keep the clock alive through power cuts ---
        // Standard switch-over is already the driver default; it is repeated
        // here so the logger's power policy is explicit. A low coin cell is
        // reported once so it can be replaced before the next outage.
        rtc.configureBatteryBackup(BatteryMode.STANDARD)                   // Select battery switch-over, (mode, lowDetection=true) → Unit
        if (rtc.isBatteryLow()) {                                          // Query battery-low flag, () → Boolean
            println("warning: backup battery low - replace the coin cell")
        }

        // --- Hourly wake-up plus a 30 s heartbeat ---
        // Only the minute field is enabled, so the alarm matches at hh:00 every
        // hour. Timer B reloads automatically and has its own INT2 pin, so the
        // heartbeat keeps running independently of the hourly alarm.
        rtc.disableClockOutput()                                           // Disable CLKOUT, () → Unit
        rtc.setAlarm(Alarm(minute = 0))                                    // Configure alarm, (Alarm(minute, hour, day, weekday)) → Unit
        rtc.configureTimerB(30, SourceClock.HZ_1)                          // Start Timer B, (value 0–255, sourceClock, pulseWidthMs=46.875 ms, pulsed=false) → Unit

        // --- Dispatch by source: log on the alarm, blink on the heartbeat ---
        val alarms = CountDownLatch(3)
        var ledOn = false
        rtc.onInterrupt({ status ->                                        // Subscribe to interrupts, (callback) → Unit
            if (status and PCF8523Full.SOURCE_ALARM != 0) {
                val dt = rtc.getDatetime()                                 // Read calendar clock, () → DateTime
                println("[hourly] %04d-%02d-%02d %02d:%02d:%02d".format(dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second))
                alarms.countDown()
            }
            if (status and PCF8523Full.SOURCE_TIMER_B != 0) {
                ledOn = !ledOn
                println("[heartbeat] LED " + if (ledOn) "on" else "off")
            }
        })
        rtc.enableInterrupt(PCF8523Full.SOURCE_ALARM or PCF8523Full.SOURCE_TIMER_B)  // Enable sources, (source) → Unit
        alarms.await()

        // --- Leave the chip quiet on exit ---
        rtc.disableInterrupt(PCF8523Full.SOURCE_ALARM or PCF8523Full.SOURCE_TIMER_B)  // Disable sources, (source) → Unit
        rtc.disableTimerB()                                                // Stop Timer B, () → Unit
        rtc.offInterrupt()                                                 // Unsubscribe, () → Unit
    }
}
