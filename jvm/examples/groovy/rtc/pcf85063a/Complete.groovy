///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-groovy:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.rtc.PCF85063AFull
import it.uhde.periph.chips.rtc.PCF85063AFull.Alarm
import it.uhde.periph.chips.rtc.PCF85063AFull.MinuteInterrupt
import it.uhde.periph.chips.rtc.PCF85063AFull.OffsetMode
import it.uhde.periph.chips.rtc.PCF85063AFull.SourceClock

def bus = System.getenv("I2C_BUS") ? Integer.parseInt(System.getenv("I2C_BUS")) : 1

def connection = new I2CConnection(bus, PCF85063AFull.DEFAULT_ADDRESS)   // open I²C bus, fixed device address, (bus, address) → I2CConnection
try {
    def rtc = new PCF85063AFull(connection)                              // Create PCF85063A Full driver, (connection) → PCF85063AFull
                                                                         // bus check, forces 24-hour mode with STOP = 0

    rtc.setDatetime(2026, 9, 23, 3, 14, 30, 0)                           // Set calendar clock, (year 2000–2099, month, day, weekday 0=Sun, hour, minute, second) → void
                                                                         // STOP-bit precision start; clears OS
    def dt = rtc.getDatetime()                                           // Read calendar clock, () → DateTime
                                                                         // decodes the seven BCD clock/calendar registers
    boolean stopped = rtc.oscillatorStopped()                            // Query oscillator-stop flag, () → boolean
                                                                         // true means the time may be invalid until setDatetime
    rtc.clearOscillatorStop()                                            // Clear oscillator-stop flag, () → void
                                                                         // still true afterwards means the crystal is not running
    rtc.stopClock()                                                      // Freeze the clock, () → void
                                                                         // STOP = 1, divider chain held in reset
    rtc.startClock()                                                     // Release the clock, () → void

    rtc.setAlarm(new Alarm(second: 0, minute: 0, hour: 9))               // Configure alarm, (Alarm(second, minute, hour, day, weekday)) → void
                                                                         // fires daily at 09:00:00; null fields are ignored in the match
    def alarm = rtc.getAlarm()                                           // Read alarm, () → Alarm
                                                                         // disabled fields decode as null

    rtc.configureTimer(10, SourceClock.HZ_1)                             // Start countdown timer, (value 1–255, sourceClock, pulsed=false) → void
                                                                         // counts down 10 s, then sets TF
    int remaining = rtc.readTimer()                                      // Read timer counter, () → int
                                                                         // live value, not the loaded one
    rtc.disableTimer()                                                   // Stop timer, () → void
                                                                         // TE = 0, TIE = 0, 1/60 Hz clock for power saving

    rtc.setMinuteInterrupt(MinuteInterrupt.MINUTE)                       // Select minute interrupt, (mode OFF|MINUTE|HALF_MINUTE) → void
                                                                         // sets MI; reported as SOURCE_TIMER

    rtc.setClockOutput(1)                                                // Drive CLKOUT, (frequencyHz) → void
                                                                         // 1 Hz square wave on the CLKOUT pin
    rtc.disableClockOutput()                                             // Disable CLKOUT, () → void
                                                                         // COF = 111, pin low

    rtc.setOffset(-3, OffsetMode.NORMAL)                                 // Write offset calibration, (offset −64–63, mode=NORMAL) → void
                                                                         // −3 LSB × 4.34 ppm = −13.02 ppm correction
    def offset = rtc.getOffset()                                         // Read offset calibration, () → Offset

    rtc.setOscillatorLoad(12.5d)                                         // Select crystal load, (picofarads 7|12.5) → void
                                                                         // match the crystal's specified load capacitance
    rtc.writeRam(0xA5)                                                   // Write RAM byte, (value 0–255) → void
    int ram = rtc.readRam()                                              // Read RAM byte, () → int

    rtc.onInterrupt({ int status -> println(String.format("interrupt status=0x%02X", status)) } as java.util.function.IntConsumer)  // Subscribe to interrupts, (callback) → void
                                                                         // falls back to 5 ms polling when no INT pin is wired
    rtc.enableInterrupt(PCF85063AFull.SOURCE_ALARM | PCF85063AFull.SOURCE_TIMER)  // Enable sources, (source) → void
                                                                         // sets AIE and TIE
    int status = rtc.pollInterrupt()                                     // Poll & clear flags, () → int
                                                                         // clears AF/TF, returns the pre-clear mask
    rtc.disableInterrupt(PCF85063AFull.SOURCE_ALARM | PCF85063AFull.SOURCE_TIMER | PCF85063AFull.SOURCE_MINUTE | PCF85063AFull.SOURCE_HALF_MINUTE | PCF85063AFull.SOURCE_CORRECTION)  // Disable sources, (source) → void
    rtc.offInterrupt()                                                   // Unsubscribe, () → void

    rtc.softwareReset()                                                  // Software reset, () → void
                                                                         // resets ALL registers including the time

    println("${dt} osStopped=${stopped}")
    println("${alarm} timer=${remaining} ram=${ram}")
    println(String.format("%s status=0x%02X", offset, status))
} finally {
    connection.close()
}
