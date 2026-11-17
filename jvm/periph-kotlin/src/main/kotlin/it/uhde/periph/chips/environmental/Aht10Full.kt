package it.uhde.periph.chips.environmental

import it.uhde.periph.connection.Connection
import kotlin.math.ln

/**
 * AHT10 — full driver. Extends [Aht10Minimal] with individual readings,
 * raw values, status inspection, explicit soft reset and dew point.
 */
class Aht10Full(
    connection: Connection
) : Aht10Minimal(connection) {

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
     * Read the status byte.
     *
     * @return status byte (bit 7 BUSY, bits 6:5 MODE, bit 3 CAL)
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
     * Send the soft reset command, wait 20 ms, and re-run the init command.
     */
    fun softReset() {
        connection.write(CMD_SOFT_RESET)
        Thread.sleep(20)
        initialize()
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
