package it.uhde.periph.chips.environmental

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * AHT20 — temperature and humidity sensor with I²C interface (minimal driver).
 *
 * Measures temperature (-50 to 150 °C) and relative humidity (0 to 100 %RH).
 * Handles power-on wait, calibration check (soft reset and calibration register
 * init if needed) and measurement triggering automatically.
 *
 * Default I²C address: 0x38.
 *
 * ## Configuration defaults
 * - One-shot measurement triggered on every read() call
 * - 80 ms wait after trigger, then BUSY polling (10 ms steps, 200 ms guard)
 * - No CRC verification (CRC check is Full-only)
 *
 * Construction throws [IOException] if the sensor is still uncalibrated after
 * the soft reset and the calibration register init.
 */
open class Aht20Minimal(
    protected val connection: Connection
) {
    companion object {
        val CMD_STATUS     = byteArrayOf(0x71.toByte())
        val CMD_TRIGGER    = byteArrayOf(0xAC.toByte(), 0x33.toByte(), 0x00.toByte())
        val CMD_SOFT_RESET = byteArrayOf(0xBA.toByte())
        val CMD_CAL_INIT   = listOf(
            byteArrayOf(0x1B.toByte(), 0x00.toByte(), 0x00.toByte()),
            byteArrayOf(0x1C.toByte(), 0x00.toByte(), 0x00.toByte()),
            byteArrayOf(0x1E.toByte(), 0x00.toByte(), 0x00.toByte())
        )

        const val STATUS_BUSY = 0x80
        const val STATUS_CAL  = 0x08
        const val STATUS_READY = 0x18
    }

    init {
        Thread.sleep(100)
        calibrate()
        Thread.sleep(10)
    }

    /**
     * Trigger a measurement and return temperature and humidity.
     *
     * Sends the trigger command, waits 80 ms, polls BUSY, reads 6 bytes, and
     * decodes the raw 20-bit values into physical units.
     *
     * @return Pair of (temperature in °C, humidity in %RH)
     */
    fun read(): Pair<Double, Double> {
        val (rawRh, rawT) = decodeRaw(measure())
        val humidityPct  = ((rawRh / 1048576.0) * 100.0).coerceIn(0.0, 100.0)
        val temperatureC = (rawT / 1048576.0) * 200.0 - 50.0
        return Pair(temperatureC, humidityPct)
    }

    private fun ready(): Boolean = (readStatus() and STATUS_READY) == STATUS_READY

    /** Datasheet calibration check: status against 0x18, then soft reset, then calibration init. */
    protected fun calibrate() {
        if (ready()) return
        connection.write(CMD_SOFT_RESET)
        Thread.sleep(20)
        if (ready()) return
        for (cmd in CMD_CAL_INIT) {
            connection.write(cmd)
            Thread.sleep(10)
        }
        if (!ready()) throw IOException("AHT20 not calibrated after init")
    }

    protected fun readStatus(): Int {
        connection.write(CMD_STATUS)
        val buf = connection.read(1)
        return buf[0].toInt() and 0xFF
    }

    /** Trigger a conversion and return the n-byte frame (6, or 7 with CRC) once BUSY is clear. */
    protected fun measure(length: Int = 6): ByteArray {
        connection.write(CMD_TRIGGER)
        Thread.sleep(80) // conversion takes > 80 ms
        var waited = 0
        while (true) {
            val data = connection.read(length)
            if ((data[0].toInt() and STATUS_BUSY) == 0) return data
            if (waited >= 200) throw IOException("AHT20 measurement timeout")
            Thread.sleep(10)
            waited += 10
        }
    }

    /** Extract (rawHumidity, rawTemperature), 20 bit each, from a response frame. */
    protected fun decodeRaw(buf: ByteArray): Pair<Int, Int> {
        val rawRh = ((buf[1].toInt() and 0xFF) shl 12) or ((buf[2].toInt() and 0xFF) shl 4) or ((buf[3].toInt() and 0xFF) shr 4)
        val rawT  = ((buf[3].toInt() and 0x0F) shl 16) or ((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF)
        return Pair(rawRh, rawT)
    }
}
