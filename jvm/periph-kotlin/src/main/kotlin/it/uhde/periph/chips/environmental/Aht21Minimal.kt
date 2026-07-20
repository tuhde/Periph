package it.uhde.periph.chips.environmental

import it.uhde.periph.transport.Transport

/**
 * AHT21 — temperature and humidity sensor with I²C interface (minimal driver).
 *
 * Measures temperature (-50 to 150 °C) and relative humidity (0 to 100 %RH).
 * Handles power-on initialization, calibration check, and measurement
 * triggering automatically.
 *
 * Default I²C address: 0x38 (fixed — not configurable).
 *
 * ## Configuration defaults
 * - Measurement triggered on every read() call (no continuous mode)
 * - 80 ms fixed wait after trigger (no busy-polling)
 * - No CRC verification (reduces complexity; CRC check is Full-only)
 */
open class Aht21Minimal(
    protected val transport: Transport
) {
    companion object {
        val CMD_TRIGGER    = byteArrayOf(0xAC.toByte(), 0x33.toByte(), 0x00.toByte())
        val CMD_SOFT_RESET = byteArrayOf(0xBA.toByte())
        val CMD_CAL_INIT_1 = byteArrayOf(0x1B.toByte(), 0x00.toByte(), 0x00.toByte())
        val CMD_CAL_INIT_2 = byteArrayOf(0x1C.toByte(), 0x00.toByte(), 0x00.toByte())
        val CMD_CAL_INIT_3 = byteArrayOf(0x1E.toByte(), 0x00.toByte(), 0x00.toByte())

        const val STATUS_BUSY = 0x80
        const val STATUS_CAL  = 0x08
    }

    init {
        Thread.sleep(100)
        var status = readStatus()
        if ((status and 0x18) != 0x18) {
            transport.write(CMD_SOFT_RESET)
            Thread.sleep(20)
            status = readStatus()
            if ((status and 0x18) != 0x18) {
                transport.write(CMD_CAL_INIT_1)
                Thread.sleep(10)
                transport.write(CMD_CAL_INIT_2)
                Thread.sleep(10)
                transport.write(CMD_CAL_INIT_3)
                Thread.sleep(10)
            }
        }
    }

    /**
     * Trigger a measurement and return temperature and humidity.
     *
     * Sends the trigger command, waits 80 ms, reads 6 bytes, and decodes
     * the raw 20-bit values into physical units.
     *
     * @return Pair of (temperature in °C, humidity in %RH)
     */
    fun read(): Pair<Double, Double> {
        transport.write(CMD_TRIGGER)
        Thread.sleep(80)
        val data = transport.read(6)
        return decode(data)
    }

    protected fun readStatus(): Int {
        val buf = transport.read(1)
        return buf[0].toInt() and 0xFF
    }

    protected fun decode(buf: ByteArray): Pair<Double, Double> {
        val rawRh = ((buf[1].toInt() and 0xFF) shl 12) or ((buf[2].toInt() and 0xFF) shl 4) or ((buf[3].toInt() and 0xFF) shr 4)
        val rawT  = ((buf[3].toInt() and 0x0F) shl 16) or ((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF)
        val humidityPct  = (rawRh / 1048576.0) * 100.0
        val temperatureC = (rawT  / 1048576.0) * 200.0 - 50.0
        return Pair(temperatureC, humidityPct)
    }
}
