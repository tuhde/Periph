///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-kotlin:1.2.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.rtc.PCF8523Full
import it.uhde.periph.chips.rtc.PCF8523Minimal
import it.uhde.periph.chips.rtc.PCF8523Full.Alarm
import it.uhde.periph.chips.rtc.PCF8523Full.BatteryMode
import it.uhde.periph.chips.rtc.PCF8523Full.OffsetMode
import it.uhde.periph.chips.rtc.PCF8523Full.SourceClock
import it.uhde.periph.chips.rtc.PCF8523Full.TimerAMode

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, PCF8523Minimal.DEFAULT_ADDRESS).use { connection ->     // open I²C bus, fixed device address, (bus, address) → I2CConnection
        val rtc = PCF8523Full(connection)                                  // Create PCF8523 Full driver, (connection) → PCF8523Full
                                                                           // enables battery switch-over standard mode (PM=000)

        rtc.setDatetime(2026, 9, 23, 3, 14, 30, 0)                         // Set calendar clock, (year, month, day, weekday 0=Sun, hour, minute, second) → Unit
                                                                           // STOP-bit precision start; forces 24-hour mode and clears OS
        val dt = rtc.getDatetime()                                         // Read calendar clock, () → DateTime
                                                                           // decodes the seven BCD clock/calendar registers
        val stopped = rtc.oscillatorStopped()                              // Query oscillator-stop flag, () → Boolean
                                                                           // true means the time may be invalid until setDatetime

        rtc.setAlarm(Alarm(minute = 0, hour = 9))                          // Configure alarm, (Alarm(minute, hour, day, weekday)) → Unit
                                                                           // fires daily at 09:00; null fields are ignored in the match
        val alarm = rtc.getAlarm()                                         // Read alarm, () → Alarm
                                                                           // disabled fields decode as null

        rtc.configureTimerA(TimerAMode.COUNTDOWN, 10, SourceClock.HZ_1)    // Start Timer A, (mode, value 0–255, sourceClock, pulsed=false) → Unit
                                                                           // counts down 10 s, then sets CTAF
        val remainingA = rtc.readTimerA()                                  // Read Timer A counter, () → Int
                                                                           // live value, not the loaded one
        rtc.disableTimerA()                                                // Stop Timer A, () → Unit

        rtc.configureTimerB(30, SourceClock.HZ_1, 62.5, pulsed = true)     // Start Timer B, (value 0–255, sourceClock, pulseWidthMs=46.875 ms, pulsed=false) → Unit
                                                                           // 30 s countdown, pulsed 62.5 ms low on INT1 and INT2
        val remainingB = rtc.readTimerB()                                  // Read Timer B counter, () → Int
        rtc.disableTimerB()                                                // Stop Timer B, () → Unit

        rtc.setClockOutput(1)                                              // Drive CLKOUT, (frequencyHz) → Unit
                                                                           // 1 Hz square wave on the shared INT1/CLKOUT pin
        rtc.disableClockOutput()                                           // Disable CLKOUT, () → Unit
                                                                           // frees INT1 for interrupts

        rtc.setOffset(-3, OffsetMode.EVERY_TWO_HOURS)                      // Write offset calibration, (offset −64–63, mode=EVERY_TWO_HOURS) → Unit
                                                                           // −3 LSB × 4.34 ppm = −13.02 ppm correction
        val offset = rtc.getOffset()                                       // Read offset calibration, () → Offset

        rtc.configureBatteryBackup(BatteryMode.STANDARD, true)             // Select battery switch-over, (mode, lowDetection=true) → Unit
                                                                           // switches to VBAT when VDD < VBAT and VDD < 2.5 V
        val switched = rtc.isBatterySwitchedOver()                         // Query switch-over flag, () → Boolean
        rtc.clearBatterySwitchover()                                       // Clear switch-over flag, () → Unit
        val low = rtc.isBatteryLow()                                       // Query battery-low flag, () → Boolean
                                                                           // read-only; clears itself once the cell is replaced

        rtc.onInterrupt({ status -> println("interrupt status=0x%02X".format(status)) })  // Subscribe to interrupts, (callback) → Unit
                                                                           // falls back to 5 ms polling when no INT pin is wired
        rtc.enableInterrupt(PCF8523Full.SOURCE_ALARM or PCF8523Full.SOURCE_TIMER_B or PCF8523Full.SOURCE_BATTERY_LOW)  // Enable sources, (source) → Unit
                                                                           // sets AIE, CTBIE and BLIE
        val status = rtc.pollInterrupt()                                   // Poll & clear flags, () → Int
                                                                           // clears CTAF/CTBF/SF/AF/BSF, returns the pre-clear mask
        rtc.disableInterrupt(PCF8523Full.SOURCE_ALARM or PCF8523Full.SOURCE_TIMER_A or PCF8523Full.SOURCE_TIMER_B or PCF8523Full.SOURCE_BATTERY_LOW)  // Disable sources, (source) → Unit
        rtc.offInterrupt()                                                 // Unsubscribe, () → Unit

        rtc.softwareReset()                                                // Software reset, () → Unit
                                                                           // control registers back to POR (PM=111); time is kept

        println("$dt osStopped=$stopped")
        println("$alarm timerA=$remainingA timerB=$remainingB")
        println("$offset switched=$switched low=$low status=0x%02X".format(status))
    }
}
