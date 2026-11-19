package it.uhde.periph.chips.humidity

import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import java.io.IOException
import kotlin.math.ln
import kotlin.math.roundToInt

/** HS4001 — same protocol as the whole HS40xx family; an alias of [HS40xxMinimal]. */
typealias HS4001Minimal = HS40xxMinimal
/** HS4003 — alias of [HS40xxMinimal]. */
typealias HS4003Minimal = HS40xxMinimal
/** HS4001 — alias of [HS40xxFull]. */
typealias HS4001Full = HS40xxFull
/** HS4003 — alias of [HS40xxFull]. */
typealias HS4003Full = HS40xxFull

/**
 * HS40xx — full driver extending [HS40xxMinimal]: single values, hold mode, resolution, periodic
 * mode, alerts, ID, dew point.
 *
 * Registers are volatile: they return to their defaults whenever the supply is cut or after
 * [softReset].
 *
 * [onInterrupt] uses `connection.intPin()` if wired (edge per the configured polarity), otherwise
 * a polling thread at the periodic interval; the callback receives the `SOURCE_*` status bitmask.
 */
open class HS40xxFull(connection: Connection) : HS40xxMinimal(connection) {
    companion object {
        /** Alert source: humidity below the low trigger threshold. */
        const val SOURCE_RH_LOW = 0x01
        /** Alert source: humidity above the high trigger threshold. */
        const val SOURCE_RH_HIGH = 0x02
        /** Alert source: temperature below the low trigger threshold. */
        const val SOURCE_TEMP_LOW = 0x04
        /** Alert source: temperature above the high trigger threshold. */
        const val SOURCE_TEMP_HIGH = 0x08

        private const val CMD_READ_REG = 0xA7
        private const val CMD_WRITE_REG = 0xA6
        private const val CMD_STOP_PERIODIC = 0x30
        private const val CMD_READ_ID = 0xD7
        private const val CMD_SOFT_RESET = 0xFE

        private const val REG_RESOLUTION = 0x00
        private const val REG_PERIODIC = 0x02
        private const val REG_ALERT_EN = 0x03

        private val BITS = intArrayOf(8, 10, 12, 14)
    }

    private var alertsEnabled = false
    private var activeHigh = true
    private var periodS = 1.0
    @Volatile private var callback: ((Int) -> Unit)? = null
    private var intPin: InputPin? = null
    @Volatile private var polling = false
    private var pollThread: Thread? = null
    private val edgeHandler = EdgeHandler { handleEdge() }

    /**
     * Measure temperature only (no-hold `0xF3`, 3 bytes, CRC verified) in °C.
     *
     * @throws IOException on bus error or CRC mismatch
     */
    fun readTemperature(): Double = toTemperature(rawTemperature(measure(CMD_T_NOHOLD, 3, false), 0))

    /** Measure humidity (uses the `0xF5` humidity + temperature command) in %RH. */
    fun readHumidity(): Double = read()[1]

    /**
     * Measure with the hold command `0xE5` (the sensor stretches SCL until done; needs a
     * clock-stretching-capable controller and SCL ≥ 200 kHz).
     *
     * @return {temperature in °C, relative humidity in %RH}
     */
    fun readHold(): DoubleArray {
        val d = measure(CMD_HT_HOLD, 5, true)
        return doubleArrayOf(toTemperature(rawTemperature(d, 2)), toHumidity(rawHumidity(d)))
    }

    /**
     * Measure and return the raw 14-bit values (CRC verified).
     *
     * @return {raw humidity, raw temperature}
     */
    fun readRaw(): IntArray {
        val d = measure(CMD_HT_NOHOLD, 5, false)
        return intArrayOf(rawHumidity(d), rawTemperature(d, 2))
    }

    /** Read one configuration register (`0xA7` + address, then a one-byte read). */
    fun readRegister(reg: Int): Int {
        connection.write(byteArrayOf(CMD_READ_REG.toByte(), reg.toByte()))
        return readFrame(1)[0].toInt() and 0xFF
    }

    /** Write one configuration register (`0xA6` + address + data). */
    fun writeRegister(reg: Int, value: Int) {
        connection.write(byteArrayOf(CMD_WRITE_REG.toByte(), reg.toByte(), value.toByte()))
    }

    private fun modify(reg: Int, mask: Int, bits: Int) {
        val v = readRegister(reg)
        writeRegister(reg, (v and mask.inv() and 0xFF) or (bits and mask))
    }

    private fun bitsCode(bits: Int): Int {
        val i = BITS.indexOf(bits)
        require(i >= 0) { "resolution must be 8, 10, 12 or 14 bits" }
        return i
    }

    /**
     * Set the measurement resolutions (register 0x00, read-modify-write).
     *
     * @param humidityBits 8, 10, 12 or 14, or null to leave unchanged
     * @param temperatureBits 8, 10, 12 or 14, or null to leave unchanged
     * @throws IllegalArgumentException for other values (before any bus access)
     */
    fun setResolution(humidityBits: Int?, temperatureBits: Int?) {
        var mask = 0
        var value = 0
        if (humidityBits != null) {
            value = value or (bitsCode(humidityBits) shl 2)
            mask = mask or 0x0C
        }
        if (temperatureBits != null) {
            value = value or bitsCode(temperatureBits)
            mask = mask or 0x03
        }
        if (mask != 0) modify(REG_RESOLUTION, mask, value)
    }

    /** Read the measurement resolutions: {humidity bits, temperature bits}, each 8, 10, 12 or 14. */
    fun getResolution(): IntArray {
        val v = readRegister(REG_RESOLUTION)
        return intArrayOf(BITS[(v shr 2) and 3], BITS[v and 3])
    }

    /**
     * Start periodic measurement mode (register 0x02 bit 7 plus the period).
     *
     * @param periodS 0.5, 1 or 2.5 seconds
     * @throws IllegalArgumentException for other periods
     */
    fun startPeriodic(periodS: Double) {
        val code = when (periodS) {
            0.5 -> 0
            1.0 -> 1
            2.5 -> 2
            else -> throw IllegalArgumentException("periodS must be 0.5, 1 or 2.5")
        }
        alertsEnabled = (readRegister(REG_ALERT_EN) and 0x0F) != 0
        this.periodS = periodS
        modify(REG_PERIODIC, 0xB0, 0x80 or (code shl 4))
    }

    /** Leave periodic mode (command `0x30`); the sensor then sleeps. */
    fun stopPeriodic() {
        connection.write(byteArrayOf(CMD_STOP_PERIODIC.toByte()))
    }

    /**
     * Fetch the latest periodic result (a plain read, CRC verified). The frame has a leading
     * status byte when any alert source is enabled.
     *
     * @return {temperature in °C, relative humidity in %RH, status} — status is the `SOURCE_*`
     *         bitmask of the active alerts (0 when alerts are off)
     */
    fun readPeriodic(): DoubleArray {
        val alerts = alertsEnabled
        var b = readFrame(if (alerts) 6 else 5)
        var status = 0
        if (alerts) {
            status = b[0].toInt() and 0x0F
            b = b.copyOfRange(1, 6)
        }
        if (!crcOk(b)) throw IOException("HS40xx CRC mismatch")
        return doubleArrayOf(toTemperature(rawTemperature(b, 2)), toHumidity(rawHumidity(b)), status.toDouble())
    }

    private fun rhWord(rh: Double): Int = (rh / 100.0 * FULL_SCALE).roundToInt().coerceIn(0, 16383)

    private fun tempWord(t: Double): Int = ((t + 40.0) / 165.0 * FULL_SCALE).roundToInt().coerceIn(0, 16383)

    private fun writeThreshold(lowReg: Int, word: Int) {
        writeRegister(lowReg, word and 0xFF)
        writeRegister(lowReg + 1, word shr 8)
    }

    /**
     * Program alert trigger / reset thresholds; null leaves a threshold unchanged.
     * Humidity values are in %RH, temperatures in °C.
     */
    fun setAlertThresholds(
        rhHigh: Double? = null, rhHighReset: Double? = null, rhLow: Double? = null, rhLowReset: Double? = null,
        tempHigh: Double? = null, tempHighReset: Double? = null, tempLow: Double? = null, tempLowReset: Double? = null,
    ) {
        rhHigh?.let { writeThreshold(0x0F, rhWord(it)) }
        rhHighReset?.let { writeThreshold(0x0D, rhWord(it)) }
        rhLow?.let { writeThreshold(0x11, rhWord(it)) }
        rhLowReset?.let { writeThreshold(0x13, rhWord(it)) }
        tempHigh?.let { writeThreshold(0x07, tempWord(it)) }
        tempHighReset?.let { writeThreshold(0x05, tempWord(it)) }
        tempLow?.let { writeThreshold(0x09, tempWord(it)) }
        tempLowReset?.let { writeThreshold(0x0B, tempWord(it)) }
    }

    /** Set the ALERT pin polarity (register 0x02 bit 0): true is active high when triggered (default). */
    fun setAlertPolarity(activeHigh: Boolean) {
        this.activeHigh = activeHigh
        modify(REG_PERIODIC, 0x01, if (activeHigh) 1 else 0)
    }

    /** Enable alert source(s) (register 0x03); [source] is a bitmask of `SOURCE_*`. */
    fun enableInterrupt(source: Int) {
        val v = readRegister(REG_ALERT_EN) or (source and 0x0F)
        writeRegister(REG_ALERT_EN, v)
        alertsEnabled = (v and 0x0F) != 0
    }

    /** Disable alert source(s) (register 0x03); [source] is a bitmask of `SOURCE_*`. */
    fun disableInterrupt(source: Int) {
        val v = readRegister(REG_ALERT_EN) and (source and 0x0F).inv() and 0xFF
        writeRegister(REG_ALERT_EN, v)
        alertsEnabled = (v and 0x0F) != 0
    }

    /**
     * Fetch the latest periodic result and return the alert status; needs periodic mode with an
     * alert source enabled.
     *
     * @return bitmask of `SOURCE_*` for the alerts currently active
     * @throws IllegalStateException if no alert source is enabled
     */
    fun pollInterrupt(): Int {
        check(alertsEnabled) { "HS40xx alerts are not enabled" }
        return readPeriodic()[2].toInt()
    }

    /**
     * Subscribe to the ALERT pin; falls back to polling at the periodic interval when no pin is wired.
     *
     * @param callback receives the `SOURCE_*` status bitmask
     * @param intPin ALERT pin to arm, or null to force the polling fallback
     */
    @JvmOverloads
    fun onInterrupt(callback: (Int) -> Unit, intPin: InputPin? = connection.intPin()) {
        offInterrupt()
        this.callback = callback
        if (intPin != null) {
            this.intPin = intPin
            intPin.onEdge(edgeHandler, if (activeHigh) EdgeTrigger.RISING else EdgeTrigger.FALLING)
        } else {
            polling = true
            val periodMs = (periodS * 1000).toLong()
            pollThread = Thread({
                while (polling) {
                    handleEdge()
                    try {
                        Thread.sleep(periodMs)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    }
                }
            }, "hs40xx-poll").also {
                it.isDaemon = true
                it.start()
            }
        }
    }

    /** Unsubscribe and stop delivery. */
    fun offInterrupt() {
        callback = null
        intPin?.offEdge(edgeHandler)
        intPin = null
        polling = false
        pollThread?.interrupt()
        pollThread = null
    }

    private fun handleEdge() {
        val cb = callback ?: return
        try {
            cb(pollInterrupt())
        } catch (_: IOException) {
            // a failed poll delivers nothing
        } catch (_: IllegalStateException) {
        }
    }

    /** Read the 32-bit sensor ID (command `0xD7`, four bytes MSB first) as an unsigned value. */
    fun readId(): Long {
        connection.write(byteArrayOf(CMD_READ_ID.toByte()))
        val d = readFrame(4)
        return ((d[0].toLong() and 0xFF) shl 24) or ((d[1].toLong() and 0xFF) shl 16) or
            ((d[2].toLong() and 0xFF) shl 8) or (d[3].toLong() and 0xFF)
    }

    /** Stop periodic mode, send the soft reset (`0xFE`) and wait 500 ms. */
    fun softReset() {
        stopPeriodic()
        connection.write(byteArrayOf(CMD_SOFT_RESET.toByte()))
        sleepMs(500)
        alertsEnabled = false
        activeHigh = true
    }

    /**
     * Run one measurement and return the dew point (Magnus formula) in °C; −273.15 if humidity is 0 %RH.
     */
    fun dewPoint(): Double {
        val m = read()
        if (m[1] <= 0.0) return -273.15
        val gamma = ln(m[1] / 100.0) + 17.62 * m[0] / (243.12 + m[0])
        return 243.12 * gamma / (17.62 - gamma)
    }
}
