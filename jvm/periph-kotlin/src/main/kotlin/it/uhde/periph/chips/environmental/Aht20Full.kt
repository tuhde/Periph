package it.uhde.periph.chips.environmental

import it.uhde.periph.connection.Connection
import kotlin.math.ln

/**
 * AHT20 — full driver. Extends [Aht20Minimal] with individual readings,
 * CRC, raw values, status inspection, explicit soft reset and dew point.
 */
class Aht20Full(
    connection: Connection
) : Aht20Minimal(connection) {

    /**
     * Trigger a measurement and return temperature only.
     *
     * @return temperature in °C (-50 to 150 °C)
     */
    fun readTemperature(): Double = read().first

    /**
     * Trigger a measurement and return humidity only.
     *
     * @return relative humidity in %RH (0 to 100 %RH)
     */
    fun readHumidity(): Double = read().second

    /**
     * Trigger a measurement and return the raw 20-bit values.
     *
     * @return Pair of (raw humidity, raw temperature), each 0 to 1048575
     */
    fun readRaw(): Pair<Int, Int> = decodeRaw(measure())

    /**
     * Trigger a measurement, read 7 bytes and verify the CRC-8 (polynomial 0x31,
     * initial value 0xFF, over bytes 0 to 5, compared with byte 6).
     *
     * @return Triple of (temperature in °C, humidity in %RH, CRC ok)
     */
    fun readWithCrc(): Triple<Double, Double, Boolean> {
        val data = measure(7)
        val (rawRh, rawT) = decodeRaw(data)
        val humidityPct  = ((rawRh / 1048576.0) * 100.0).coerceIn(0.0, 100.0)
        val temperatureC = (rawT / 1048576.0) * 200.0 - 50.0
        return Triple(temperatureC, humidityPct, crc8(data, 6) == (data[6].toInt() and 0xFF))
    }

    /** CRC-8 (polynomial 0x31, initial value 0xFF) over the first [length] bytes. */
    fun crc8(data: ByteArray, length: Int): Int {
        var crc = 0xFF
        for (i in 0 until length) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 0x80) != 0) ((crc shl 1) xor 0x31) and 0xFF else (crc shl 1) and 0xFF
            }
        }
        return crc
    }

    /**
     * Read the status byte.
     *
     * @return status byte (bit 7 BUSY, bit 3 CAL)
     */
    fun readStatusByte(): Int = readStatus()

    /**
     * Check if the calibration bit is set in the status byte.
     *
     * @return true if the sensor reports calibration enabled
     */
    fun isCalibrated(): Boolean = (readStatus() and STATUS_CAL) != 0

    /**
     * Check if the busy bit is set in the status byte.
     *
     * @return true if a measurement is in progress
     */
    fun isBusy(): Boolean = (readStatus() and STATUS_BUSY) != 0

    /**
     * Send the soft reset command, wait 20 ms, and re-run the calibration check.
     */
    fun softReset() {
        connection.write(CMD_SOFT_RESET)
        Thread.sleep(20)
        calibrate()
    }

    /**
     * Trigger a measurement and return the dew point (Magnus formula).
     *
     * @return dew point in °C; -50.0 if humidity is 0 %RH
     */
    fun dewPoint(): Double {
        val (t, rh) = read()
        if (rh <= 0.0) return -50.0
        val gamma = ln(rh / 100.0) + 17.62 * t / (243.12 + t)
        return 243.12 * gamma / (17.62 - gamma)
    }
}
