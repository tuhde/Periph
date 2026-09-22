///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.0
//DEPS it.uhde:periph-kotlin:1.2.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.rtc.DS3231Full
import it.uhde.periph.chips.rtc.DS3231Minimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, DS3231Minimal.DEFAULT_ADDRESS).use { connection ->
        val rtc = DS3231Full(connection)                                    // construct driver and confirm presence, (connection) → DS3231Full

        // --- Data-logger backup clock: reseed the time if the chip lost power ---
        // The Oscillator Stop Flag latches whenever the oscillator has stopped —
        // first power-up, a dead coin cell, or an external disturbance. On a fresh
        // or just-recovered chip the clock/calendar registers may hold stale or
        // undefined data, so reseed from a known reference before trusting them.
        // setDatetime() also clears OSF, so a healthy chip only reseeds once.
        if (rtc.oscillatorStopped()) {
            rtc.setDatetime(2026, 9, 22, 2, 0, 0, 0)                        // Reseed calendar clock, (year, month, day, weekday, hour, minute, second) → None
            println("oscillator was stopped; reseeded clock and cleared OSF")
        }

        // --- Configure a minute heartbeat and an hourly checkpoint alarm ---
        // Alarm 1 fires once per minute (at :00 seconds); Alarm 2 fires once per
        // hour (at :00 minutes). Both repeat automatically — their registers are
        // never rewritten — so enabling the interrupt is all that's needed to
        // keep them firing for the life of the demo.
        rtc.setAlarm1(0, 0, 0, 0, false, DS3231Full.ALARM1_MATCH_SECONDS)
        rtc.setAlarm2(0, 0, 0, false, DS3231Full.ALARM2_MATCH_HOURS_MINUTES)
        rtc.enableInterrupt(DS3231Full.SOURCE_ALARM1)
        rtc.enableInterrupt(DS3231Full.SOURCE_ALARM2)

        // --- Log a line on every alarm match until 5 heartbeats have fired ---
        // Each callback masks the returned status against SOURCE_ALARM1/
        // SOURCE_ALARM2 and logs the current date/time plus the on-chip
        // temperature for whichever source(s) matched — a one-line "log entry".
        val targetHeartbeats = 5
        val heartbeats = AtomicInteger(0)
        val done = CountDownLatch(1)

        rtc.onInterrupt({ status ->
            try {
                val dt = rtc.getDatetime()
                val tempC = rtc.readTemperature()
                if (status and DS3231Full.SOURCE_ALARM1 != 0) {
                    println("[heartbeat] $dt  %.2f C".format(tempC))
                    if (heartbeats.incrementAndGet() >= targetHeartbeats) done.countDown()
                }
                if (status and DS3231Full.SOURCE_ALARM2 != 0) {
                    println("[checkpoint] $dt  %.2f C".format(tempC))
                }
            } catch (e: Exception) {
                System.err.println("log entry failed: ${e.message}")
            }
        })

        done.await()

        // --- Clean shutdown ---
        rtc.disableInterrupt(DS3231Full.SOURCE_ALARM1)
        rtc.disableInterrupt(DS3231Full.SOURCE_ALARM2)
        rtc.offInterrupt()
        println("demo complete")
    }
}
