package it.uhde.periph.chips.humidity

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * SHTC3 — relative humidity and temperature sensor (Sensirion), minimal driver.
 *
 * Each [read] runs a full measurement cycle: wake-up (0x3517), a normal-mode temperature-first
 * measurement without clock stretching (0x7866), a 13 ms wait, a 6-byte read with both CRC-8
 * bytes verified, and the sleep command (0xB098). A bad CRC is retried once. I²C address 0x70.
 * The chip is command driven (no register pointer), so a plain [Connection] is used.
 *
 * ## Configuration defaults
 * - Nothing is written at construction; [init] performs the soft reset and the ID check
 * - Normal mode, no clock stretching, the sensor sleeps after every reading
 * - Relative humidity is clamped to 0–100 %RH; both CRCs are verified
 */
open class Shtc3Minimal(protected val connection: Connection) {
    companion object {
        const val CMD_SLEEP   = 0xB098
        const val CMD_WAKE    = 0x3517
        const val CMD_RESET   = 0x805D
        const val CMD_READ_ID = 0xEFC8
        const val ID_MASK     = 0x083F
        const val ID_VALUE    = 0x0807

        /** Measurement commands [lowPowerMode][stretching], temperature first. */
        val CMD_MEASURE = arrayOf(intArrayOf(0x7866, 0x7CA2), intArrayOf(0x609C, 0x6458))

        /**
         * CRC-8 used by the SHTC3: polynomial 0x31 (x^8 + x^5 + x^4 + 1), init 0xFF, no final XOR.
         */
        fun crc8(data: ByteArray, off: Int, len: Int): Int {
            var crc = 0xFF
            for (i in off until off + len) {
                crc = crc xor (data[i].toInt() and 0xFF)
                repeat(8) {
                    crc = if ((crc and 0x80) != 0) ((crc shl 1) xor 0x31) and 0xFF else (crc shl 1) and 0xFF
                }
            }
            return crc
        }

        fun toHumidity(code: Int): Double = (100.0 * code / 65536.0).coerceIn(0.0, 100.0)

        fun toTemperature(code: Int): Double = -45.0 + 175.0 * code / 65536.0

        /** CRC-verified 16-bit word at [off]. */
        fun word(data: ByteArray, off: Int): Int {
            if (crc8(data, off, 2) != (data[off + 2].toInt() and 0xFF)) throw IOException("SHTC3 CRC mismatch")
            return ((data[off].toInt() and 0xFF) shl 8) or (data[off + 1].toInt() and 0xFF)
        }
    }

    protected var lowPowerMode = false
    protected var stretching = false
    protected var sleepAfterRead = true
    protected var asleep = false

    protected fun sleepMs(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    protected fun command(code: Int) = connection.write(byteArrayOf((code shr 8).toByte(), code.toByte()))

    protected fun wakeIfAsleep() {
        if (asleep) {
            command(CMD_WAKE)
            sleepMs(1)
            asleep = false
        }
    }

    protected fun goToSleep() {
        command(CMD_SLEEP)
        asleep = true
    }

    protected fun readIdWord(): Int {
        command(CMD_READ_ID)
        return word(connection.read(3), 0)
    }

    /** One measurement cycle; returns {t_raw, rh_raw}. A bad CRC is retried once. */
    protected fun measure(): IntArray {
        wakeIfAsleep()
        try {
            return measureOnce()
        } finally {
            if (sleepAfterRead) goToSleep()
        }
    }

    private fun measureOnce(): IntArray {
        var last: IOException? = null
        repeat(2) {
            command(CMD_MEASURE[if (lowPowerMode) 1 else 0][if (stretching) 1 else 0])
            if (!stretching) sleepMs(if (lowPowerMode) 1 else 13)
            val data = connection.read(6)
            try {
                return intArrayOf(word(data, 0), word(data, 3))
            } catch (e: IOException) {
                last = e
            }
        }
        throw last!!
    }

    /**
     * Wake, soft reset (0x805D), check the product ID, then sleep.
     *
     * @throws IOException on bus error, CRC mismatch or a wrong product code
     */
    fun init() {
        asleep = true
        wakeIfAsleep()
        command(CMD_RESET)
        sleepMs(1)
        val id = try {
            readIdWord()
        } finally {
            goToSleep()
        }
        if ((id and ID_MASK) != ID_VALUE) throw IOException("not an SHTC3 (ID 0x%04X)".format(id))
    }

    /**
     * Measure temperature and humidity.
     *
     * @return {temperature in °C, relative humidity in %RH (0–100)}
     * @throws IOException on bus error or CRC mismatch
     */
    fun read(): DoubleArray {
        val w = measure()
        return doubleArrayOf(toTemperature(w[0]), toHumidity(w[1]))
    }
}
