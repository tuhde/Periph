package it.uhde.periph.chips.humidity

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * Si7021 — relative humidity and temperature sensor (Silicon Labs), minimal driver.
 *
 * Each [read] runs one relative-humidity measurement without hold master (the read header is
 * polled until the chip ACKs, timeout 50 ms), verifies the CRC and then fetches the temperature
 * of that same measurement with command 0xE0. I²C address 0x40 (fixed).
 *
 * ## Configuration defaults
 * - Nothing is written at construction; power-on defaults: 12-bit RH / 14-bit temperature, heater off
 * - No-hold-master measurement (0xF5), polling the NACKed read header
 * - CRC-8 verified on the RH result; relative humidity clamped to 0–100 %RH
 *
 * The constructor does no bus access.
 */
open class Si7021Minimal(protected val connection: Connection) {
    companion object {
        const val CMD_RH_HOLD    = 0xE5
        const val CMD_RH_POLL    = 0xF5
        const val CMD_T_HOLD     = 0xE3
        const val CMD_T_POLL     = 0xF3
        const val CMD_T_PREVIOUS = 0xE0
        const val CMD_RESET      = 0xFE
        const val CMD_WRITE_USER = 0xE6
        const val CMD_READ_USER  = 0xE7
        const val POLL_ATTEMPTS  = 50

        /**
         * CRC-8 used by the Si7021: polynomial 0x31, init 0x00, no reflection, no final XOR.
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

        fun toHumidity(code: Int): Double = minOf(100.0, maxOf(0.0, 125.0 * code / 65536.0 - 6.0))

        fun toTemperature(code: Int): Double = 175.72 * code / 65536.0 - 46.85
    }

    protected var stretch = false

    protected fun sleepMs(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    protected fun command(code: Int) = connection.write(byteArrayOf(code.toByte()))

    /** Run one measurement command and return the CRC-verified raw 16-bit code. */
    protected fun measure(holdCmd: Int, pollCmd: Int): Int {
        var data: ByteArray? = null
        if (stretch) {
            command(holdCmd)
            data = connection.read(3)
        } else {
            command(pollCmd)
            var i = 0
            while (i < POLL_ATTEMPTS && data == null) {
                try {
                    data = connection.read(3)
                } catch (nack: IOException) {
                    sleepMs(1)                          // read header NACKed: not ready yet
                }
                i++
            }
            if (data == null) throw IOException("Si7021 measurement did not complete")
        }
        if (crc8(data!!, 0, 2) != (data[2].toInt() and 0xFF)) throw IOException("Si7021 CRC mismatch")
        return ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
    }

    protected fun previousCode(): Int {
        command(CMD_T_PREVIOUS)
        val d = connection.read(2)
        return ((d[0].toInt() and 0xFF) shl 8) or (d[1].toInt() and 0xFF)
    }

    /**
     * Run one RH measurement and read the temperature of the same conversion.
     *
     * @return {temperature in °C, relative humidity in %RH (0–100)}
     * @throws IOException on bus error, timeout (50 ms) or a CRC mismatch
     */
    fun read(): DoubleArray {
        val rh = measure(CMD_RH_HOLD, CMD_RH_POLL)
        val t = previousCode()
        return doubleArrayOf(toTemperature(t), toHumidity(rh))
    }
}
