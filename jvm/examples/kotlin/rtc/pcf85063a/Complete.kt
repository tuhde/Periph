///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.3
//DEPS it.uhde:periph-kotlin:1.3.3

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.rtc.PCF85063AFull
import it.uhde.periph.chips.rtc.PCF85063AMinimal
import it.uhde.periph.chips.rtc.PCF85063AFull.Alarm
import it.uhde.periph.chips.rtc.PCF85063AFull.MinuteInterrupt
import it.uhde.periph.chips.rtc.PCF85063AFull.OffsetMode
import it.uhde.periph.chips.rtc.PCF85063AFull.SourceClock

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, PCF85063AMinimal.DEFAULT_ADDRESS).use { connection ->  // open I²C bus, fixed device address, (bus, address) → I2CConnection
        val rtc = PCF85063AFull(connection)                                 // Create PCF85063A Full driver, (connection) → PCF85063AFull
                                                                            // bus check, forces 24-hour mode with STOP = 0

        rtc.setDatetime(2026, 9, 23, 3, 14, 30, 0)                          // Set calendar clock, (year 2000–2099, month, day, weekday 0=Sun, hour, minute, second) → Unit
                                                                            // STOP-bit precision start; clears OS
        val dt = rtc.getDatetime()                                          // Read calendar clock, () → DateTime
                                                                            // decodes the seven BCD clock/calendar registers
        val stopped = rtc.oscillatorStopped()                               // Query oscillator-stop flag, () → Boolean
                                                                            // true means the time may be invalid until setDatetime
        rtc.clearOscillatorStop()                                           // Clear oscillator-stop flag, () → Unit
                                                                            // still true afterwards means the crystal is not running
        rtc.stopClock()                                                     // Freeze the clock, () → Unit
                                                                            // STOP = 1, divider chain held in reset
        rtc.startClock()                                                    // Release the clock, () → Unit

        rtc.setAlarm(Alarm(second = 0, minute = 0, hour = 9))               // Configure alarm, (Alarm(second, minute, hour, day, weekday)) → Unit
                                                                            // fires daily at 09:00:00; null fields are ignored in the match
        val alarm = rtc.getAlarm()                                          // Read alarm, () → Alarm
                                                                            // disabled fields decode as null

        rtc.configureTimer(10, SourceClock.HZ_1)                            // Start countdown timer, (value 1–255, sourceClock, pulsed=false) → Unit
                                                                            // counts down 10 s, then sets TF
        val remaining = rtc.readTimer()                                     // Read timer counter, () → Int
                                                                            // live value, not the loaded one
        rtc.disableTimer()                                                  // Stop timer, () → Unit
                                                                            // TE = 0, TIE = 0, 1/60 Hz clock for power saving

        rtc.setMinuteInterrupt(MinuteInterrupt.MINUTE)                      // Select minute interrupt, (mode OFF|MINUTE|HALF_MINUTE) → Unit
                                                                            // sets MI; reported as SOURCE_TIMER

        rtc.setClockOutput(1)                                               // Drive CLKOUT, (frequencyHz) → Unit
                                                                            // 1 Hz square wave on the CLKOUT pin
        rtc.disableClockOutput()                                            // Disable CLKOUT, () → Unit
                                                                            // COF = 111, pin low

        rtc.setOffset(-3, OffsetMode.NORMAL)                                // Write offset calibration, (offset −64–63, mode=NORMAL) → Unit
                                                                            // −3 LSB × 4.34 ppm = −13.02 ppm correction
        val offset = rtc.getOffset()                                        // Read offset calibration, () → Offset

        rtc.setOscillatorLoad(12.5)                                         // Select crystal load, (picofarads 7|12.5) → Unit
                                                                            // match the crystal's specified load capacitance
        rtc.writeRam(0xA5)                                                  // Write RAM byte, (value 0–255) → Unit
        val ram = rtc.readRam()                                             // Read RAM byte, () → Int

        rtc.onInterrupt({ status -> println("interrupt status=0x%02X".format(status)) })  // Subscribe to interrupts, (callback) → Unit
                                                                            // falls back to 5 ms polling when no INT pin is wired
        rtc.enableInterrupt(PCF85063AFull.SOURCE_ALARM or PCF85063AFull.SOURCE_TIMER)  // Enable sources, (source) → Unit
                                                                            // sets AIE and TIE
        val status = rtc.pollInterrupt()                                    // Poll & clear flags, () → Int
                                                                            // clears AF/TF, returns the pre-clear mask
        rtc.disableInterrupt(PCF85063AFull.SOURCE_ALARM or PCF85063AFull.SOURCE_TIMER or PCF85063AFull.SOURCE_MINUTE or PCF85063AFull.SOURCE_HALF_MINUTE or PCF85063AFull.SOURCE_CORRECTION)  // Disable sources, (source) → Unit
        rtc.offInterrupt()                                                  // Unsubscribe, () → Unit

        rtc.softwareReset()                                                 // Software reset, () → Unit
                                                                            // resets ALL registers including the time

        println("$dt osStopped=$stopped")
        println("$alarm timer=$remaining ram=$ram")
        println("$offset status=0x%02X".format(status))
    }
}
