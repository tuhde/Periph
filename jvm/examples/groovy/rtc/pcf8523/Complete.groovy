///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.0
//DEPS it.uhde:periph-groovy:1.2.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.rtc.PCF8523Full
import it.uhde.periph.chips.rtc.PCF8523Full.Alarm
import it.uhde.periph.chips.rtc.PCF8523Full.BatteryMode
import it.uhde.periph.chips.rtc.PCF8523Full.OffsetMode
import it.uhde.periph.chips.rtc.PCF8523Full.SourceClock
import it.uhde.periph.chips.rtc.PCF8523Full.TimerAMode

def bus = System.getenv("I2C_BUS") ? Integer.parseInt(System.getenv("I2C_BUS")) : 1

def connection = new I2CConnection(bus, PCF8523Full.DEFAULT_ADDRESS)     // open I²C bus, fixed device address, (bus, address) → I2CConnection
try {
    def rtc = new PCF8523Full(connection)                               // Create PCF8523 Full driver, (connection) → PCF8523Full
                                                                        // enables battery switch-over standard mode (PM=000)

    rtc.setDatetime(2026, 9, 23, 3, 14, 30, 0)                          // Set calendar clock, (year, month, day, weekday 0=Sun, hour, minute, second) → void
                                                                        // STOP-bit precision start; forces 24-hour mode and clears OS
    def dt = rtc.getDatetime()                                          // Read calendar clock, () → DateTime
                                                                        // decodes the seven BCD clock/calendar registers
    def stopped = rtc.oscillatorStopped()                               // Query oscillator-stop flag, () → boolean
                                                                        // true means the time may be invalid until setDatetime

    rtc.setAlarm(new Alarm(minute: 0, hour: 9))                         // Configure alarm, (Alarm(minute, hour, day, weekday)) → void
                                                                        // fires daily at 09:00; null fields are ignored in the match
    def alarm = rtc.getAlarm()                                          // Read alarm, () → Alarm
                                                                        // disabled fields decode as null

    rtc.configureTimerA(TimerAMode.COUNTDOWN, 10, SourceClock.HZ_1)     // Start Timer A, (mode, value 0–255, sourceClock, pulsed=false) → void
                                                                        // counts down 10 s, then sets CTAF
    def remainingA = rtc.readTimerA()                                   // Read Timer A counter, () → int
                                                                        // live value, not the loaded one
    rtc.disableTimerA()                                                 // Stop Timer A, () → void

    rtc.configureTimerB(30, SourceClock.HZ_1, 62.5d, true)              // Start Timer B, (value 0–255, sourceClock, pulseWidthMs=46.875 ms, pulsed=false) → void
                                                                        // 30 s countdown, pulsed 62.5 ms low on INT1 and INT2
    def remainingB = rtc.readTimerB()                                   // Read Timer B counter, () → int
    rtc.disableTimerB()                                                 // Stop Timer B, () → void

    rtc.setClockOutput(1)                                               // Drive CLKOUT, (frequencyHz) → void
                                                                        // 1 Hz square wave on the shared INT1/CLKOUT pin
    rtc.disableClockOutput()                                            // Disable CLKOUT, () → void
                                                                        // frees INT1 for interrupts

    rtc.setOffset(-3, OffsetMode.EVERY_TWO_HOURS)                       // Write offset calibration, (offset −64–63, mode=EVERY_TWO_HOURS) → void
                                                                        // −3 LSB × 4.34 ppm = −13.02 ppm correction
    def offset = rtc.getOffset()                                        // Read offset calibration, () → Offset

    rtc.configureBatteryBackup(BatteryMode.STANDARD, true)              // Select battery switch-over, (mode, lowDetection=true) → void
                                                                        // switches to VBAT when VDD < VBAT and VDD < 2.5 V
    def switched = rtc.isBatterySwitchedOver()                          // Query switch-over flag, () → boolean
    rtc.clearBatterySwitchover()                                        // Clear switch-over flag, () → void
    def low = rtc.isBatteryLow()                                        // Query battery-low flag, () → boolean
                                                                        // read-only; clears itself once the cell is replaced

    rtc.onInterrupt({ int status -> println(String.format("interrupt status=0x%02X", status)) } as java.util.function.IntConsumer)  // Subscribe to interrupts, (callback) → void
                                                                        // falls back to 5 ms polling when no INT pin is wired
    rtc.enableInterrupt(PCF8523Full.SOURCE_ALARM | PCF8523Full.SOURCE_TIMER_B | PCF8523Full.SOURCE_BATTERY_LOW)  // Enable sources, (source) → void
                                                                        // sets AIE, CTBIE and BLIE
    def status = rtc.pollInterrupt()                                    // Poll & clear flags, () → int
                                                                        // clears CTAF/CTBF/SF/AF/BSF, returns the pre-clear mask
    rtc.disableInterrupt(PCF8523Full.SOURCE_ALARM | PCF8523Full.SOURCE_TIMER_A | PCF8523Full.SOURCE_TIMER_B | PCF8523Full.SOURCE_BATTERY_LOW)  // Disable sources, (source) → void
    rtc.offInterrupt()                                                  // Unsubscribe, () → void

    rtc.softwareReset()                                                 // Software reset, () → void
                                                                        // control registers back to POR (PM=111); time is kept

    println("${dt} osStopped=${stopped}")
    println("${alarm} timerA=${remainingA} timerB=${remainingB}")
    println(String.format("%s switched=%b low=%b status=0x%02X", offset, switched, low, status))
} finally {
    connection.close()
}
