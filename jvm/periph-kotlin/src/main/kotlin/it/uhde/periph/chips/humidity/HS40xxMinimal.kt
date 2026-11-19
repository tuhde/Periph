package it.uhde.periph.chips.humidity

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * HS40xx — relative humidity and temperature sensors (Renesas HS4001, HS4003), minimal driver.
 *
 * HS4001 and HS4003 share one protocol, so [HS4001Minimal] and [HS4003Minimal] are type aliases
 * (HS4002 and the HS401x variants differ only physically and get no alias). Each [read] sends the
 * no-hold humidity + temperature command (0xF5), waits 3 ms, reads five bytes (retrying while the
 * sensor NACKs the read header, for up to 20 ms), verifies the CRC-8 and converts. A CRC mismatch
 * is retried once. I²C address 0x54 (fixed).
 *
 * ## Configuration defaults
 * - Nothing is written at construction
 * - Power-on state: 14-bit resolution, one-shot no-hold measurements, CRC checked, periodic mode
 *   and alerts off
 */
open class HS40xxMinimal(protected val connection: Connection) {
    companion object {
        const val FULL_SCALE = 16383.0
        const val CMD_T_NOHOLD = 0xF3
        const val CMD_HT_HOLD = 0xE5
        const val CMD_HT_NOHOLD = 0xF5

        /** CRC-8 (polynomial 0x1D, initial value 0xFF, no reflection, no final XOR). */
        fun crc8(data: ByteArray): Int {
            var crc = 0xFF
            for (b in data) {
                crc = crc xor (b.toInt() and 0xFF)
                repeat(8) { crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x1D) and 0xFF else (crc shl 1) and 0xFF }
            }
            return crc
        }

        /** True if the last byte of [f] is the CRC of its data (3-byte frames: humidity bytes taken as zero). */
        fun crcOk(f: ByteArray): Boolean {
            val data = if (f.size == 3) byteArrayOf(0, 0, f[0], f[1]) else byteArrayOf(f[0], f[1], f[2], f[3])
            return crc8(data) == (f[f.size - 1].toInt() and 0xFF)
        }

        fun rawHumidity(d: ByteArray): Int = ((d[0].toInt() and 0x3F) shl 8) or (d[1].toInt() and 0xFF)

        fun rawTemperature(d: ByteArray, offset: Int): Int =
            ((d[offset].toInt() and 0x3F) shl 8) or (d[offset + 1].toInt() and 0xFF)

        fun toHumidity(raw: Int): Double = raw / FULL_SCALE * 100.0

        fun toTemperature(raw: Int): Double = raw / FULL_SCALE * 165.0 - 40.0

        fun sleepMs(ms: Long) {
            try {
                Thread.sleep(ms)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /** Read [len] bytes, retrying while the sensor NACKs the read header (up to 20 ms). */
    protected fun readFrame(len: Int): ByteArray {
        var last: IOException? = null
        repeat(21) {
            try {
                return connection.read(len)
            } catch (e: IOException) {
                last = e
                sleepMs(1)
            }
        }
        throw last!!
    }

    /** Send [cmd], wait (no-hold), read [len] bytes and verify the CRC; the sequence is retried once on a mismatch. */
    protected fun measure(cmd: Int, len: Int, hold: Boolean): ByteArray {
        repeat(2) {
            connection.write(byteArrayOf(cmd.toByte()))
            if (!hold) sleepMs(3)
            val f = readFrame(len)
            if (crcOk(f)) return f
        }
        throw IOException("HS40xx CRC mismatch")
    }

    /**
     * Measure temperature and humidity (no-hold `0xF5`, CRC verified).
     *
     * @return {temperature in °C (−40…125), relative humidity in %RH (0…100)}
     * @throws IOException on bus error, a read NACKed for more than 20 ms, or a CRC mismatch after one retry
     */
    fun read(): DoubleArray {
        val d = measure(CMD_HT_NOHOLD, 5, false)
        return doubleArrayOf(toTemperature(rawTemperature(d, 2)), toHumidity(rawHumidity(d)))
    }
}
