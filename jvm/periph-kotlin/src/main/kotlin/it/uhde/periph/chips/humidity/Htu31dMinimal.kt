package it.uhde.periph.chips.humidity

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * HTU31D — relative humidity and temperature sensor (TE Connectivity), minimal driver.
 *
 * Each [read] starts one conversion at the highest resolution (command 0x5E, OSR 3 for humidity
 * and temperature), waits 25 ms, reads the temperature and humidity words (command 0x00) and
 * verifies both CRC-8 bytes. I²C address 0x40 or 0x41 (`IC_Add` pin). The chip is command driven
 * (no register pointer), so a plain [Connection] is used.
 *
 * ## Configuration defaults
 * - Nothing is written at construction; [init] performs the soft reset
 * - OSR 3 for humidity and temperature, heater off
 * - Relative humidity is clamped to 0–100 %RH; both CRCs are verified
 */
open class Htu31dMinimal(protected val connection: Connection) {
    companion object {
        const val CMD_RESET       = 0x1E
        const val CMD_HEATER_ON   = 0x04
        const val CMD_HEATER_OFF  = 0x02
        const val CMD_CONVERT     = 0x40
        const val CMD_READ_TRH    = 0x00
        const val CMD_READ_RH     = 0x10
        const val CMD_READ_DIAG   = 0x08
        const val CMD_READ_SERIAL = 0x0A

        /** Typical humidity conversion time in seconds for OSR 0..3. */
        val T_RH = doubleArrayOf(0.00111, 0.00214, 0.00421, 0.00834)
        /** Typical temperature conversion time in seconds for OSR 0..3. */
        val T_T = doubleArrayOf(0.00157, 0.00306, 0.00603, 0.01198)

        /**
         * CRC-8 used by the HTU31D: polynomial 0x31 (x^8 + x^5 + x^4 + 1), init 0x00, no final XOR.
         */
        fun crc8(data: ByteArray, off: Int, len: Int): Int {
            var crc = 0x00
            for (i in off until off + len) {
                crc = crc xor (data[i].toInt() and 0xFF)
                repeat(8) {
                    crc = if ((crc and 0x80) != 0) ((crc shl 1) xor 0x31) and 0xFF else (crc shl 1) and 0xFF
                }
            }
            return crc
        }

        fun toHumidity(code: Int): Double = (100.0 * code / 65535.0).coerceIn(0.0, 100.0)

        fun toTemperature(code: Int): Double = -40.0 + 165.0 * code / 65535.0
    }

    protected var osrRh = 3
    protected var osrT = 3

    protected fun sleepMs(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    protected fun command(code: Int) = connection.write(byteArrayOf(code.toByte()))

    /** Soft reset (0x1E) and wait 15 ms; heater off, highest resolution. */
    fun init() {
        command(CMD_RESET)
        sleepMs(15)
    }

    protected fun start() = command(CMD_CONVERT or (osrRh shl 3) or (osrT shl 1))

    protected fun waitMs(): Long =
        if (osrRh == 3 && osrT == 3) 25 else Math.ceil(1.25 * (T_RH[osrRh] + T_T[osrT]) * 1000.0).toLong()

    /** Write a read command and return the CRC-verified 16-bit words. */
    protected fun readWords(command: Int, count: Int): IntArray {
        command(command)
        val data = connection.read(3 * count)
        return IntArray(count) { i ->
            if (crc8(data, 3 * i, 2) != (data[3 * i + 2].toInt() and 0xFF)) throw IOException("HTU31D CRC mismatch")
            ((data[3 * i].toInt() and 0xFF) shl 8) or (data[3 * i + 1].toInt() and 0xFF)
        }
    }

    protected fun measure(command: Int, count: Int): IntArray {
        start()
        sleepMs(waitMs())
        return readWords(command, count)
    }

    /**
     * Measure temperature and humidity.
     *
     * @return {temperature in °C, relative humidity in %RH (0–100)}
     * @throws IOException on bus error or CRC mismatch
     */
    fun read(): DoubleArray {
        val w = measure(CMD_READ_TRH, 2)
        return doubleArrayOf(toTemperature(w[0]), toHumidity(w[1]))
    }
}
