package it.uhde.periph.chips.environmental

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * AHT10 — temperature and humidity sensor with I²C interface (minimal driver).
 *
 * Measures temperature (-50 to 150 °C) and relative humidity (0 to 100 %RH).
 * Handles power-on wait, initialization (0xE1 calibration enable), calibration
 * check and measurement triggering automatically.
 *
 * Default I²C address: 0x38.
 *
 * ## Configuration defaults
 * - One-shot measurement triggered on every read() call
 * - 80 ms wait after trigger, then BUSY polling (10 ms steps, 200 ms guard)
 * - No CRC (the AHT10 does not send one)
 *
 * Construction throws [IOException] if the sensor is still uncalibrated after
 * the init command and one soft-reset retry.
 */
open class Aht10Minimal(
    protected val connection: Connection
) {
    companion object {
        val CMD_INIT       = byteArrayOf(0xE1.toByte(), 0x08.toByte(), 0x00.toByte())
        val CMD_TRIGGER    = byteArrayOf(0xAC.toByte(), 0x33.toByte(), 0x00.toByte())
        val CMD_SOFT_RESET = byteArrayOf(0xBA.toByte())

        const val STATUS_BUSY = 0x80
        const val STATUS_CAL  = 0x08
    }

    init {
        Thread.sleep(40)
        initialize()
        if ((readStatus() and STATUS_CAL) == 0) {
            connection.write(CMD_SOFT_RESET)
            Thread.sleep(20)
            initialize()
            if ((readStatus() and STATUS_CAL) == 0) {
                throw IOException("AHT10 not calibrated after init")
            }
        }
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

    /** Send the init (calibration enable) command and wait 10 ms. */
    protected fun initialize() {
        connection.write(CMD_INIT)
        Thread.sleep(10)
    }

    protected fun readStatus(): Int {
        val buf = connection.read(1)
        return buf[0].toInt() and 0xFF
    }

    /** Trigger a conversion and return the 6-byte frame once BUSY is clear. */
    protected fun measure(): ByteArray {
        connection.write(CMD_TRIGGER)
        Thread.sleep(80) // conversion takes > 75 ms
        var waited = 0
        while (true) {
            val data = connection.read(6)
            if ((data[0].toInt() and STATUS_BUSY) == 0) return data
            if (waited >= 200) throw IOException("AHT10 measurement timeout")
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
