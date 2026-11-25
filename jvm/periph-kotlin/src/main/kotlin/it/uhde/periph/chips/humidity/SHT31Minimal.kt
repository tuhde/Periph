package it.uhde.periph.chips.humidity

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * SHT31 — digital temperature and humidity sensor (Sensirion), minimal driver.
 *
 * Each [read] runs one single-shot measurement at high repeatability with clock stretching
 * disabled and CRC checking on. The heater stays off. I²C address 0x44 (ADDR low) or 0x45
 * (ADDR high).
 *
 * ## Configuration defaults
 * - Soft reset (0x30A2) at construction with 1 ms waits before and after
 * - Single shot, high repeatability (0x2400), fixed 15 ms wait; if the read header is still
 *   NACKed the driver keeps polling for up to 20 ms more
 * - Both CRC-8 bytes verified; one retry of the whole measurement on a mismatch, then an [IOException]
 *
 * @throws IOException on bus error during the soft reset
 */
open class SHT31Minimal(protected val connection: Connection) {
    companion object {
        const val CMD_SOFT_RESET   = 0x30A2
        const val CMD_HEATER_ON    = 0x306D
        const val CMD_HEATER_OFF   = 0x3066
        const val CMD_READ_STATUS  = 0xF32D
        const val CMD_CLEAR_STATUS = 0x3041
        const val CMD_FETCH        = 0xE000
        const val CMD_BREAK        = 0x3093
        const val CMD_ART          = 0x2B32

        /** Single-shot commands without clock stretching, indexed low / medium / high. */
        val SINGLE_NO_STRETCH = intArrayOf(0x2416, 0x240B, 0x2400)
        /** Single-shot commands with clock stretching, indexed low / medium / high. */
        val SINGLE_STRETCH = intArrayOf(0x2C10, 0x2C0D, 0x2C06)
        /** Conversion wait in ms, indexed low / medium / high. */
        val SINGLE_WAIT_MS = intArrayOf(4, 6, 15)

        /**
         * CRC-8 used by the SHT3x: polynomial 0x31, init 0xFF, no reflection, no final XOR.
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

        /** Verify both CRCs of a 6-byte result and return the raw (temperature, humidity) words, or null on a mismatch. */
        fun decode(d: ByteArray): IntArray? {
            if (crc8(d, 0, 2) != (d[2].toInt() and 0xFF) || crc8(d, 3, 2) != (d[5].toInt() and 0xFF)) return null
            return intArrayOf(((d[0].toInt() and 0xFF) shl 8) or (d[1].toInt() and 0xFF),
                ((d[3].toInt() and 0xFF) shl 8) or (d[4].toInt() and 0xFF))
        }

        fun convert(rawT: Int, rawRh: Int): DoubleArray =
            doubleArrayOf(-45.0 + 175.0 * rawT / 65535.0, 100.0 * rawRh / 65535.0)
    }

    protected var level = 2           // 0 low, 1 medium, 2 high
    protected var stretch = false

    init {
        sleepMs(1)
        command(CMD_SOFT_RESET)
        sleepMs(1)
    }

    protected fun sleepMs(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    protected fun command(code: Int) = connection.write(byteArrayOf((code shr 8).toByte(), code.toByte()))

    /** One single-shot measurement; returns the raw (temperature, humidity) words. */
    protected fun singleShot(): IntArray {
        for (attempt in 0 until 2) {
            command(if (stretch) SINGLE_STRETCH[level] else SINGLE_NO_STRETCH[level])
            if (!stretch) sleepMs(SINGLE_WAIT_MS[level].toLong())
            var data: ByteArray? = null
            var i = 0
            while (i < 21 && data == null) {
                try {
                    data = connection.read(6)
                } catch (nack: IOException) {
                    sleepMs(1)                          // read header NACKed: not ready yet
                }
                i++
            }
            if (data == null) throw IOException("SHT31 measurement did not complete")
            val raw = decode(data)
            if (raw != null) return raw
        }
        throw IOException("SHT31 CRC mismatch")
    }

    /**
     * Run one single-shot measurement.
     *
     * @return {temperature in °C, relative humidity in %RH}
     * @throws IOException on bus error, timeout or a CRC mismatch that persists after one retry
     */
    fun read(): DoubleArray {
        val raw = singleShot()
        return convert(raw[0], raw[1])
    }
}
