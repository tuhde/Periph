package it.uhde.periph.chips.humidity

import it.uhde.periph.connection.Connection
import java.io.IOException
import kotlin.math.ln

/** HS3001 — same protocol as the whole HS30xx family; an alias of [HS30xxMinimal]. */
typealias HS3001Minimal = HS30xxMinimal
/** HS3002 — alias of [HS30xxMinimal]. */
typealias HS3002Minimal = HS30xxMinimal
/** HS3003 — alias of [HS30xxMinimal]. */
typealias HS3003Minimal = HS30xxMinimal
/** HS3001 — alias of [HS30xxFull]. */
typealias HS3001Full = HS30xxFull
/** HS3002 — alias of [HS30xxFull]. */
typealias HS3002Full = HS30xxFull
/** HS3003 — alias of [HS30xxFull]. */
typealias HS3003Full = HS30xxFull

/**
 * HS30xx — full driver extending [HS30xxMinimal]: raw values, wait override, NVM resolution and
 * ID, dew point.
 *
 * The NVM methods (resolution, ID) need the sensor's programming mode, which can only be entered
 * within 10 ms after power-up. They therefore take a `powerCycle` callback that cuts and restores
 * the sensor supply (for example via a MOSFET or a GPIO powering the sensor); with `null` they
 * throw [IllegalStateException] before any bus access.
 */
open class HS30xxFull(connection: Connection) : HS30xxMinimal(connection) {

    private val regRhRead = 0x06
    private val regRhWrite = 0x46
    private val regTRead = 0x11
    private val regTWrite = 0x51
    private val regIdHigh = 0x1E
    private val regIdLow = 0x1F
    private val nvmOk = 0x81
    private val resMask = 0x0C00
    private val bits = intArrayOf(8, 10, 12, 14)

    /** Measure temperature only (four-byte fetch, humidity discarded) in °C. */
    fun readTemperature(): Double = toTemperature(rawTemperature(fetch(4)))

    /** Measure humidity only (two-byte fetch) in %RH. */
    fun readHumidity(): Double = toHumidity(rawHumidity(fetch(2)))

    /** Measure and return {raw humidity, raw temperature, status} (14-bit values, status 0 = valid, 1 = stale) without a stale check. */
    fun readRaw(): IntArray {
        val d = fetchRaw(4)
        return intArrayOf(rawHumidity(d), rawTemperature(d), (d[0].toInt() and 0xC0) shr 6)
    }

    /**
     * Override the wait between measurement request and data fetch (default 40 ms).
     * Lower resolutions convert faster: 0.1 ms + RH_time/2 + T_time/2 (datasheet).
     *
     * @param ms wait in milliseconds, at least 1
     */
    fun setMeasurementWait(ms: Long) {
        require(ms >= 1) { "ms must be >= 1" }
        waitMs = ms
    }

    /** Send the enter-programming-mode command (0xA0 0x00 0x00) and wait; only valid within 10 ms after power-up. */
    fun enterProgrammingMode() {
        connection.write(byteArrayOf(0xA0.toByte(), 0x00, 0x00))
        sleepMs(1)
    }

    /** Send the leave-programming-mode command (0x80 0x00 0x00). */
    fun exitProgrammingMode() {
        connection.write(byteArrayOf(0x80.toByte(), 0x00, 0x00))
    }

    /**
     * Read one 16-bit NVM register (programming mode only).
     *
     * @param reg NVM read address (0x06, 0x11, 0x1E or 0x1F)
     * @throws IOException on bus error or if the response status byte is not 0x81
     */
    fun readNvm(reg: Int): Int {
        connection.write(byteArrayOf(reg.toByte(), 0x00, 0x00))
        sleepMs(1)
        val d = connection.read(3)
        if ((d[0].toInt() and 0xFF) != nvmOk) {
            throw IOException("HS30xx NVM read failed (status 0x%02X)".format(d[0].toInt() and 0xFF))
        }
        return ((d[1].toInt() and 0xFF) shl 8) or (d[2].toInt() and 0xFF)
    }

    private fun writeNvm(reg: Int, value: Int) {
        connection.write(byteArrayOf(reg.toByte(), (value shr 8).toByte(), value.toByte()))
        sleepMs(14)
    }

    private fun program(powerCycle: (() -> Unit)?) {
        checkNotNull(powerCycle) { "HS30xx NVM access needs a powerCycle callback" }
        powerCycle()
        enterProgrammingMode()
    }

    private fun bitsCode(value: Int): Int {
        val i = bits.indexOf(value)
        require(i >= 0) { "resolution must be 8, 10, 12 or 14 bits" }
        return i
    }

    /**
     * Read the NVM resolution settings (power-cycles the sensor).
     *
     * @param powerCycle cuts and restores the sensor supply; the programming-mode command follows immediately
     * @return {humidity bits, temperature bits}, each 8, 10, 12 or 14
     */
    fun getResolution(powerCycle: (() -> Unit)?): IntArray {
        program(powerCycle)
        try {
            val rh = readNvm(regRhRead)
            val t = readNvm(regTRead)
            return intArrayOf(bits[(rh shr 10) and 3], bits[(t shr 10) and 3])
        } finally {
            exitProgrammingMode()
        }
    }

    /**
     * Write the NVM resolution settings (power-cycles the sensor; bits 11:10 only).
     * Each register is read, bits 11:10 replaced and the whole word written back, then the driver
     * waits 14 ms for the NVM commit.
     *
     * @param humidityBits 8, 10, 12 or 14, or null to leave unchanged
     * @param temperatureBits 8, 10, 12 or 14, or null to leave unchanged
     * @param powerCycle cuts and restores the sensor supply
     * @throws IllegalArgumentException for any other resolution (before any bus access)
     */
    fun setResolution(humidityBits: Int?, temperatureBits: Int?, powerCycle: (() -> Unit)?) {
        val rhCode = humidityBits?.let { bitsCode(it) } ?: -1
        val tCode = temperatureBits?.let { bitsCode(it) } ?: -1
        program(powerCycle)
        try {
            if (rhCode >= 0) writeNvm(regRhWrite, (readNvm(regRhRead) and resMask.inv() and 0xFFFF) or (rhCode shl 10))
            if (tCode >= 0) writeNvm(regTWrite, (readNvm(regTRead) and resMask.inv() and 0xFFFF) or (tCode shl 10))
        } finally {
            exitProgrammingMode()
        }
    }

    /**
     * Read the 32-bit sensor ID from NVM (power-cycles the sensor).
     *
     * @param powerCycle cuts and restores the sensor supply
     * @return the 32-bit ID (unsigned)
     */
    fun readId(powerCycle: (() -> Unit)?): Long {
        program(powerCycle)
        try {
            val high = readNvm(regIdHigh).toLong()
            val low = readNvm(regIdLow).toLong()
            return (high shl 16) or low
        } finally {
            exitProgrammingMode()
        }
    }

    /** Measure once and compute the dew point in °C (Magnus formula); −273.15 if the humidity is 0 %RH. */
    fun dewPoint(): Double {
        val m = read()
        val t = m[0]
        val rh = m[1]
        if (rh <= 0.0) return -273.15
        val gamma = ln(rh / 100.0) + 17.62 * t / (243.12 + t)
        return 243.12 * gamma / (17.62 - gamma)
    }
}
