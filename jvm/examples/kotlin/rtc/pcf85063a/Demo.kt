///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.3
//DEPS it.uhde:periph-kotlin:1.3.3

// Scheduling core of a battery-powered logger: reseeds the clock after a
// power loss, counts boots in the free RAM byte, then wakes on an hourly
// alarm to print a timestamp while a 30-second pulsed countdown timer
// toggles an LED as a "still running" heartbeat.

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.rtc.PCF85063AFull
import it.uhde.periph.chips.rtc.PCF85063AMinimal
import it.uhde.periph.chips.rtc.PCF85063AFull.Alarm
import it.uhde.periph.chips.rtc.PCF85063AFull.MinuteInterrupt
import it.uhde.periph.chips.rtc.PCF85063AFull.SourceClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, PCF85063AMinimal.DEFAULT_ADDRESS).use { connection ->  // open I²C bus, fixed device address, (bus, address) → I2CConnection
        val rtc = PCF85063AFull(connection)                                 // Create PCF85063A Full driver, (connection) → PCF85063AFull

        // --- Detect a lost time reference and reseed if needed ---
        // A fresh chip, or one whose supply dropped out, reports the OS flag
        // set: its calendar cannot be trusted until reseeded. The crystal
        // needs up to 2 s to start, so wait until the flag stays clear.
        if (rtc.oscillatorStopped()) {                                      // Query oscillator-stop flag, () → Boolean
            rtc.setDatetime(2026, 1, 1, 4, 0, 0, 0)                         // Set calendar clock, (year, month, day, weekday 0=Sun, hour, minute, second) → Unit
            println("oscillator was stopped - reseeded from reference timestamp")
            while (rtc.oscillatorStopped()) {                               // Query oscillator-stop flag, () → Boolean
                Thread.sleep(100)
            }
        }

        // --- Count boots in the free RAM byte ---
        // The RAM byte survives as long as VDD is present, so it works as a
        // tiny non-volatile counter next to the clock.
        val boots = (rtc.readRam() + 1) and 0xFF                            // Read RAM byte, () → Int
        rtc.writeRam(boots)                                                 // Write RAM byte, (value 0–255) → Unit
        println("boot #$boots ${rtc.getDatetime()} ${rtc.getOffset()}")     // Read calendar clock, () → DateTime
                                                                            // Read offset calibration, () → Offset

        // --- Tune the board for low power ---
        // Match the crystal's load capacitance and switch the 32.768 kHz
        // CLKOUT off — it is enabled at power-on and costs current on a
        // battery device.
        rtc.setOscillatorLoad(12.5)                                         // Select crystal load, (picofarads 7|12.5) → Unit
        rtc.disableClockOutput()                                            // Disable CLKOUT, () → Unit

        // --- Hourly wake-up plus a 30 s heartbeat ---
        // Only second and minute are enabled, so the alarm matches at
        // hh:00:00 every hour. The pulsed timer reloads automatically; minute
        // ticks share the same TF flag and are reported as SOURCE_TIMER.
        rtc.setAlarm(Alarm(second = 0, minute = 0))                         // Configure alarm, (Alarm(second, minute, hour, day, weekday)) → Unit
        rtc.configureTimer(30, SourceClock.HZ_1, true)                      // Start countdown timer, (value 1–255, sourceClock, pulsed=false) → Unit
        rtc.setMinuteInterrupt(MinuteInterrupt.MINUTE)                      // Select minute interrupt, (mode OFF|MINUTE|HALF_MINUTE) → Unit

        // --- Dispatch by source: log on the alarm, blink on the heartbeat ---
        val alarms = CountDownLatch(5)
        var ledOn = false
        rtc.onInterrupt({ status ->                                         // Subscribe to interrupts, (callback) → Unit
            if (status and PCF85063AFull.SOURCE_ALARM != 0) {
                val dt = rtc.getDatetime()                                  // Read calendar clock, () → DateTime
                println("[hourly] %04d-%02d-%02d %02d:%02d:%02d".format(dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second))
                alarms.countDown()
            }
            if (status and PCF85063AFull.SOURCE_TIMER != 0) {
                ledOn = !ledOn
                println("[heartbeat] LED " + if (ledOn) "on" else "off")
            }
        })
        rtc.enableInterrupt(PCF85063AFull.SOURCE_ALARM or PCF85063AFull.SOURCE_TIMER)  // Enable sources, (source) → Unit
        alarms.await(5, TimeUnit.MINUTES)

        // --- Leave the chip quiet on exit ---
        rtc.disableInterrupt(PCF85063AFull.SOURCE_ALARM or PCF85063AFull.SOURCE_TIMER)  // Disable sources, (source) → Unit
        rtc.disableTimer()                                                  // Stop timer, () → Unit
        rtc.setMinuteInterrupt(MinuteInterrupt.OFF)                         // Select minute interrupt, (mode OFF|MINUTE|HALF_MINUTE) → Unit
        rtc.offInterrupt()                                                  // Unsubscribe, () → Unit
    }
}
