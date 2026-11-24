package it.uhde.periph.chips.humidity

import it.uhde.periph.connection.Connection
import java.io.IOException
import kotlin.math.ln

/**
 * SHTC3 — full driver extending [Shtc3Minimal]: single values and raw codes, low-power mode,
 * clock stretching, sleep control, soft reset, the product ID and the dew point.
 */
open class Shtc3Full(connection: Connection) : Shtc3Minimal(connection) {

    /** Run one measurement cycle and return the temperature in °C. */
    fun readTemperature(): Double = toTemperature(measure()[0])

    /** Run one measurement cycle and return the humidity in %RH, clamped to 0–100. */
    fun readHumidity(): Double = toHumidity(measure()[1])

    /** Run one measurement cycle and return the raw 16-bit codes {raw temperature, raw humidity}. */
    fun readRaw(): IntArray = measure()

    /** Use the low-power (0.7 ms, waits 1 ms) or the normal (10.8 ms, waits 13 ms) measurement commands. */
    fun setLowPower(on: Boolean) {
        lowPowerMode = on
    }

    /** Use the clock-stretching command set; the fixed wait is skipped (needs a controller that tolerates SCL held low). */
    fun setClockStretching(on: Boolean) {
        stretching = on
    }

    /** Choose whether a read ends with the sleep command (default on). */
    fun setSleepBetween(on: Boolean) {
        sleepAfterRead = on
    }

    /** Send the wake-up command (0x3517) and wait 1 ms. */
    fun wake() {
        command(CMD_WAKE)
        sleepMs(1)
        asleep = false
    }

    /** Send the sleep command (0xB098). */
    fun sleep() = goToSleep()

    /** Soft reset (0x805D), 1 ms wait; leaves the sensor idle. */
    fun softReset() {
        wakeIfAsleep()
        command(CMD_RESET)
        sleepMs(1)
    }

    /**
     * Read the 16-bit ID word (command 0xEFC8, CRC verified); `(id and 0x083F) == 0x0807` for the
     * SHTC3. A sleeping sensor is woken for the read and put back to sleep.
     *
     * @throws IOException on bus error or CRC mismatch
     */
    fun readId(): Int {
        val wasAsleep = asleep
        wakeIfAsleep()
        try {
            return readIdWord()
        } finally {
            if (wasAsleep) goToSleep()
        }
    }

    /** Run one measurement and compute the dew point in °C (Magnus formula over water). */
    fun dewPoint(): Double {
        val m = read()
        val t = m[0]
        val rh = if (m[1] <= 0.0) 0.01 else m[1]
        val g = ln(rh / 100.0) + 17.62 * t / (243.12 + t)
        return 243.12 * g / (17.62 - g)
    }
}
