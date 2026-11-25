package it.uhde.periph.chips.pressure

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * MS5611 — barometric pressure sensor (TE Connectivity), minimal driver.
 *
 * [init] resets the chip, reads the eight PROM words and verifies the CRC-4. Each [read] converts
 * the temperature (D2) and then the pressure (D1) at oversampling ratio 4096 (waiting 10 ms each)
 * and applies the first- and second-order compensation. Pressure is in pascals (1 Pa = 0.01 mbar,
 * the chip's native unit). I²C address 0x77 (CSB = GND) or 0x76 (CSB = VDD). The chip is command
 * driven (no register pointer), so a plain [Connection] is used.
 *
 * ## Configuration defaults
 * - Nothing is written at construction; [init] resets the chip and reads the PROM
 * - Oversampling ratio 4096 for both conversions
 * - Second-order temperature compensation applied
 */
open class Ms5611Minimal(protected val connection: Connection) {
    companion object {
        const val CMD_RESET      = 0x1E
        const val CMD_CONVERT_D1 = 0x40
        const val CMD_CONVERT_D2 = 0x50
        const val CMD_ADC_READ   = 0x00
        const val CMD_PROM_READ  = 0xA0

        /** Supported oversampling ratios. */
        val OSR_VALUES = intArrayOf(256, 512, 1024, 2048, 4096)

        /** Maximum conversion time per OSR in microseconds (datasheet). */
        val T_CONV_US = intArrayOf(600, 1170, 2280, 4540, 9040)

        /**
         * CRC-4 of the PROM (application note AN520): polynomial x^4 + x^3 + 1 over the 16 bytes of
         * the eight words, MSB first; the low byte of word 7 (including the CRC nibble) counts as zero.
         */
        fun crc4(words: IntArray): Int {
            val p = words.copyOf()
            p[7] = p[7] and 0xFF00
            var rem = 0
            for (cnt in 0 until 16) {
                rem = rem xor (if ((cnt and 1) != 0) (p[cnt shr 1] and 0x00FF) else (p[cnt shr 1] shr 8))
                repeat(8) {
                    rem = if ((rem and 0x8000) != 0) ((rem shl 1) xor 0x3000) and 0xFFFF else (rem shl 1) and 0xFFFF
                }
            }
            return (rem shr 12) and 0x0F
        }
    }

    protected var osrIndex = 4
    protected var prom = IntArray(8)

    protected fun sleepMs(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    protected fun readPromWords(): IntArray {
        val words = IntArray(8)
        for (n in 0 until 8) {
            connection.write(byteArrayOf((CMD_PROM_READ + 2 * n).toByte()))
            val b = connection.read(2)
            words[n] = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
        }
        return words
    }

    protected fun readAdcValue(): Int {
        connection.write(byteArrayOf(CMD_ADC_READ.toByte()))
        val b = connection.read(3)
        val v = ((b[0].toInt() and 0xFF) shl 16) or ((b[1].toInt() and 0xFF) shl 8) or (b[2].toInt() and 0xFF)
        if (v == 0) throw IOException("MS5611 ADC returned 0 (conversion not finished or not started)")
        return v
    }

    protected fun start(kind: Int, index: Int) {
        val base = if (kind == 1) CMD_CONVERT_D2 else CMD_CONVERT_D1
        connection.write(byteArrayOf((base or (index shl 1)).toByte()))
    }

    protected fun convert(kind: Int): Int {
        start(kind, osrIndex)
        sleepMs(((T_CONV_US[osrIndex] + 999) / 1000 + 1).toLong())
        return readAdcValue()
    }

    /** Datasheet integer algorithm (64-bit, truncating division); returns {°C, Pa}. */
    protected fun compute(d1: Int, d2: Int, secondOrder: Boolean): DoubleArray {
        val c1 = prom[1].toLong(); val c2 = prom[2].toLong(); val c3 = prom[3].toLong()
        val c4 = prom[4].toLong(); val c5 = prom[5].toLong(); val c6 = prom[6].toLong()
        val dt = d2.toLong() - c5 * 256
        var temp = 2000 + dt * c6 / (1L shl 23)
        var off = c2 * (1L shl 16) + c4 * dt / (1L shl 7)
        var sens = c1 * (1L shl 15) + c3 * dt / (1L shl 8)
        if (secondOrder && temp < 2000) {
            val d = temp - 2000
            val t2 = dt * dt / (1L shl 31)
            var off2 = 5 * d * d / 2
            var sens2 = 5 * d * d / 4
            if (temp < -1500) {
                val e = temp + 1500
                off2 += 7 * e * e
                sens2 += 11 * e * e / 2
            }
            temp -= t2
            off -= off2
            sens -= sens2
        }
        val p = (d1.toLong() * sens / (1L shl 21) - off) / (1L shl 15)
        return doubleArrayOf(temp / 100.0, p.toDouble())
    }

    /**
     * Reset (0x1E), wait 3 ms, read the PROM and verify its CRC-4.
     *
     * @throws IOException on bus error or a PROM CRC mismatch
     */
    fun init() {
        connection.write(byteArrayOf(CMD_RESET.toByte()))
        sleepMs(3)
        val words = readPromWords()
        if (crc4(words) != (words[7] and 0x0F)) throw IOException("MS5611 PROM CRC mismatch")
        prom = words
    }

    /**
     * Measure temperature and pressure (OSR 4096, second-order compensation).
     *
     * @return {temperature in °C, pressure in Pa}
     * @throws IOException on bus error or an ADC result of 0
     */
    fun read(): DoubleArray {
        val d2 = convert(1)
        val d1 = convert(0)
        return compute(d1, d2, true)
    }
}
