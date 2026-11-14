package it.uhde.periph.chips.environmental

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * AM2315 — humidity and temperature sensor probe (Aosong), minimal driver.
 *
 * The sensor speaks a Modbus-style protocol: every read is a wake-up (a write the sensor does not
 * acknowledge), a command write, a 2 ms wait and a read whose response carries a CRC-16. The
 * sensor measures after each communication and returns that result on the next one, so [read]
 * returns the *previous* measurement; transactions are kept at least 2 s apart by sleeping the
 * remainder. I²C address 0x5C (fixed, standard mode, at most 100 kHz).
 *
 * ## Configuration defaults
 * - Nothing is written at construction; the first read waits until 2 s after construction (power-up settling)
 * - At least 2 s between transactions
 * - One retry after a new wake-up on a CRC mismatch or bus error
 */
open class AM2315Minimal(protected val connection: Connection) {

    /** The sensor answered with an error response (function code with bit 7 set); [code] is 0x80–0x84. */
    class ErrorResponse(val code: Int) : IOException("AM2315 error response 0x%02X".format(code))

    companion object {
        const val FUNC_READ = 0x03
        const val FUNC_WRITE = 0x10

        /** CRC-16 (Modbus) used by the AM2315: init 0xFFFF, polynomial 0xA001 (reflected). */
        fun crc16(data: ByteArray, off: Int, len: Int): Int {
            var crc = 0xFFFF
            for (i in off until off + len) {
                crc = crc xor (data[i].toInt() and 0xFF)
                repeat(8) {
                    crc = if ((crc and 1) != 0) (crc shr 1) xor 0xA001 else crc shr 1
                }
            }
            return crc
        }

        fun toHumidity(raw: Int): Double = raw / 10.0

        fun toTemperature(raw: Int): Double {
            val t = (raw and 0x7FFF) / 10.0
            return if ((raw and 0x8000) != 0) -t else t
        }

        fun word(d: ByteArray, i: Int): Int = ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)
    }

    private var minIntervalMs = 2000L
    private var last = System.nanoTime()

    /** Override the minimum interval between transactions (default 2000 ms); intended for tests. */
    fun setMinIntervalMs(ms: Long) {
        minIntervalMs = ms
    }

    protected fun sleepMs(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun waitInterval() {
        if (minIntervalMs <= 0) return
        val remaining = minIntervalMs - (System.nanoTime() - last) / 1_000_000L
        if (remaining > 0) sleepMs(remaining)
    }

    /** Wake the sensor: a dummy write (the NACK is ignored) and a 1 ms wait. */
    protected fun wakeSensor() {
        try {
            connection.write(byteArrayOf(0x00))      // the sleeping sensor NACKs this
        } catch (expected: IOException) {
            // ignored
        }
        sleepMs(1)
    }

    private fun exchange(frame: ByteArray, responseLen: Int): ByteArray {
        waitInterval()
        wakeSensor()
        try {
            connection.write(frame)
            sleepMs(2)
            return connection.read(responseLen)
        } finally {
            last = System.nanoTime()
        }
    }

    private fun check(data: ByteArray, function: Int, responseLen: Int) {
        if (data.isNotEmpty() && (data[0].toInt() and 0x80) != 0) throw ErrorResponse(data[0].toInt() and 0xFF)
        if (data.size < responseLen) throw IOException("AM2315 short response")
        val crc = (data[responseLen - 2].toInt() and 0xFF) or ((data[responseLen - 1].toInt() and 0xFF) shl 8)
        if ((data[0].toInt() and 0xFF) != function || crc16(data, 0, responseLen - 2) != crc) {
            throw IOException("AM2315 CRC mismatch")
        }
    }

    /** One command/response exchange with a retry on a bus or CRC error; [frame] is the full command frame. */
    protected fun transact(frame: ByteArray, responseLen: Int, function: Int): ByteArray {
        var failure: IOException? = null
        repeat(2) {
            try {
                val data = exchange(frame, responseLen)
                check(data, function, responseLen)
                return data
            } catch (e: ErrorResponse) {
                throw e
            } catch (e: IOException) {
                failure = e
            }
        }
        throw failure!!
    }

    /** Read [count] (1–10) registers from [start]; the response is CRC verified. */
    protected fun readRegistersRaw(start: Int, count: Int): ByteArray {
        val data = transact(byteArrayOf(FUNC_READ.toByte(), start.toByte(), count.toByte()), count + 4, FUNC_READ)
        if ((data[1].toInt() and 0xFF) != count) throw IOException("AM2315 unexpected byte count")
        return data.copyOfRange(2, 2 + count)
    }

    /**
     * Read temperature and humidity (registers 0x00–0x03); the result is the previous measurement.
     *
     * @return {temperature in °C, relative humidity in %RH}
     * @throws IOException on bus error, two CRC mismatches or a sensor error response ([ErrorResponse])
     */
    fun read(): DoubleArray {
        val d = readRegistersRaw(0x00, 4)
        return doubleArrayOf(toTemperature(word(d, 2)), toHumidity(word(d, 0)))
    }
}
