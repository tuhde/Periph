package it.uhde.periph.chips.humidity

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * HS30xx — relative humidity and temperature sensors (Renesas HS3001, HS3002, HS3003), minimal driver.
 *
 * The three parts share one protocol and differ only in accuracy class and housing, so
 * [HS3001Minimal], [HS3002Minimal] and [HS3003Minimal] are type aliases. Each [read] sends a
 * measurement request (an address-only write), waits 40 ms, fetches four bytes (data fetch) and
 * converts them; stale data are fetched once more after 10 ms. I²C address 0x44 (fixed).
 *
 * ## Configuration defaults
 * - Nothing is written at construction: bytes written within 10 ms of power-up could be taken as
 *   NVM programming commands
 * - Factory resolution (14-bit humidity and temperature), fixed 40 ms wait, no NVM access
 */
open class HS30xxMinimal(protected val connection: Connection) {
    companion object {
        const val FULL_SCALE = 16383.0

        fun rawHumidity(d: ByteArray): Int = ((d[0].toInt() and 0x3F) shl 8) or (d[1].toInt() and 0xFF)

        fun rawTemperature(d: ByteArray): Int = ((d[2].toInt() and 0xFF) shl 6) or ((d[3].toInt() and 0xFF) shr 2)

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

    protected var waitMs: Long = 40

    /** Send the measurement request (address-only write), wait and read [len] bytes without a stale check. */
    protected fun fetchRaw(len: Int): ByteArray {
        connection.write(ByteArray(0))
        sleepMs(waitMs)
        return connection.read(len)
    }

    /** [fetchRaw] plus one retry after 10 ms if the status bits say stale. */
    protected fun fetch(len: Int): ByteArray {
        var data = fetchRaw(len)
        if ((data[0].toInt() and 0xC0) != 0) {
            sleepMs(10)
            data = connection.read(len)
            if ((data[0].toInt() and 0xC0) != 0) throw IOException("HS30xx returned stale data")
        }
        return data
    }

    /**
     * Measure temperature and humidity (the output is always scaled to 14 bits).
     *
     * @return {temperature in °C (−40…125), relative humidity in %RH (0…100)}
     * @throws IOException on bus error or if the data are still stale after one retry
     */
    fun read(): DoubleArray {
        val d = fetch(4)
        return doubleArrayOf(toTemperature(rawTemperature(d)), toHumidity(rawHumidity(d)))
    }
}
