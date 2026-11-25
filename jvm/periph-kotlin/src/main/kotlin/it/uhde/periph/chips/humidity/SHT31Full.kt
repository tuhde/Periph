package it.uhde.periph.chips.humidity

import it.uhde.periph.connection.Connection
import java.io.IOException
import kotlin.math.ln

/**
 * SHT31 — full driver extending [SHT31Minimal]: single values and raw words, the three
 * repeatability levels, the clock-stretching command set, periodic measurements (0.5–10 mps
 * plus ART), the heater, the status register, soft reset and the dew point.
 */
open class SHT31Full(connection: Connection) : SHT31Minimal(connection) {

    private val periodic = arrayOf(
        intArrayOf(0x202F, 0x2024, 0x2032),
        intArrayOf(0x212D, 0x2126, 0x2130),
        intArrayOf(0x222B, 0x2220, 0x2236),
        intArrayOf(0x2329, 0x2322, 0x2334),
        intArrayOf(0x272A, 0x2721, 0x2737),
    )

    private fun levelIndex(name: String): Int = when (name) {
        "low" -> 0
        "medium" -> 1
        "high" -> 2
        else -> throw IllegalArgumentException("level must be 'low', 'medium' or 'high'")
    }

    /** Run one measurement and return the temperature only (°C). */
    fun readTemperature(): Double = read()[0]

    /** Run one measurement and return the relative humidity only (%RH). */
    fun readHumidity(): Double = read()[1]

    /** Run one measurement and return the raw 16-bit words {temperature, humidity}. */
    fun readRaw(): IntArray = singleShot()

    /**
     * Select the repeatability for later single-shot reads (measurement time 4 / 6 / 15 ms).
     *
     * @param repeatability "low", "medium" or "high"
     */
    fun setRepeatability(repeatability: String) {
        level = levelIndex(repeatability)
    }

    /**
     * Use the clock-stretching command set for single-shot reads (the bus layer must support it);
     * the fixed wait is skipped.
     */
    fun setClockStretching(on: Boolean) {
        stretch = on
    }

    /**
     * Start periodic measurements; read results with [fetch].
     *
     * @param rate measurements per second: 0.5, 1, 2, 4 or 10 (ignored with [art])
     * @param repeatability "low", "medium" or "high"
     * @param art true for the accelerated response time mode (4 mps, command 0x2B32)
     */
    @JvmOverloads
    fun startPeriodic(rate: Double, repeatability: String = "high", art: Boolean = false) {
        if (art) {
            command(CMD_ART)
            return
        }
        val row = when (rate) {
            0.5 -> 0
            1.0 -> 1
            2.0 -> 2
            4.0 -> 3
            10.0 -> 4
            else -> throw IllegalArgumentException("rate must be 0.5, 1, 2, 4 or 10")
        }
        command(periodic[row][levelIndex(repeatability)])
    }

    /**
     * Read the latest periodic result (command 0xE000).
     *
     * @return {temperature in °C, relative humidity in %RH}, or null if no new data is available
     * (the read header is NACKed)
     * @throws IOException on a CRC mismatch
     */
    fun fetch(): DoubleArray? {
        command(CMD_FETCH)
        val data = try {
            connection.read(6)
        } catch (nack: IOException) {
            return null
        }
        val raw = decode(data) ?: throw IOException("SHT31 CRC mismatch")
        return convert(raw[0], raw[1])
    }

    /** Stop periodic mode (break command 0x3093) and wait 1 ms. */
    fun stopPeriodic() {
        command(CMD_BREAK)
        sleepMs(1)
    }

    /** Switch the on-chip heater (about 5–25 mW) on or off. */
    fun setHeater(on: Boolean) = command(if (on) CMD_HEATER_ON else CMD_HEATER_OFF)

    /**
     * Read the status register (command 0xF32D, CRC verified).
     *
     * @return 16-bit status, e.g. 0x8010 after reset; bit 13 = heater on
     */
    fun readStatus(): Int {
        command(CMD_READ_STATUS)
        val d = connection.read(3)
        if (crc8(d, 0, 2) != (d[2].toInt() and 0xFF)) throw IOException("SHT31 CRC mismatch")
        return ((d[0].toInt() and 0xFF) shl 8) or (d[1].toInt() and 0xFF)
    }

    /** Clear the status register flags (command 0x3041). */
    fun clearStatus() = command(CMD_CLEAR_STATUS)

    /** Soft reset (0x30A2), wait 1 ms; leaves periodic mode and returns to single shot. */
    fun softReset() {
        command(CMD_SOFT_RESET)
        sleepMs(1)
    }

    /** Run one measurement and compute the dew point (Magnus formula, °C). */
    fun dewPoint(): Double {
        val v = read()
        val t = v[0]
        val rh = if (v[1] <= 0.0) 0.01 else v[1]
        val gamma = ln(rh / 100.0) + (17.62 * t) / (243.12 + t)
        return 243.12 * gamma / (17.62 - gamma)
    }
}
