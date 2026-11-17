package it.uhde.periph.chips.environmental

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/**
 * AHT10 — full driver. Extends {@link Aht10Minimal} with individual readings,
 * raw values, status inspection, explicit soft reset and dew point.
 */
@CompileStatic
class Aht10Full extends Aht10Minimal {

    /**
     * Construct the full driver and perform power-on initialization.
     *
     * @param connection I²C connection bound to the AHT10 device address (0x38)
     */
    Aht10Full(Connection connection) {
        super(connection)
    }

    /**
     * Trigger a measurement and return temperature only.
     *
     * @return temperature in °C (-50 to 150 °C)
     */
    double readTemperature() {
        read()[0]
    }

    /**
     * Trigger a measurement and return humidity only.
     *
     * @return relative humidity in %RH (0 to 100 %RH)
     */
    double readHumidity() {
        read()[1]
    }

    /**
     * Trigger a measurement and return the raw 20-bit values.
     *
     * @return int array: [0] = raw humidity, [1] = raw temperature (each 0 to 1048575)
     */
    int[] readRaw() {
        decodeRaw(measure())
    }

    /**
     * Read the status byte.
     *
     * @return status byte (bit 7 BUSY, bits 6:5 MODE, bit 3 CAL)
     */
    int readStatusByte() {
        readStatus()
    }

    /**
     * Check if the calibration bit is set in the status byte.
     *
     * @return true if the sensor reports calibration enabled
     */
    boolean isCalibrated() {
        (readStatus() & STATUS_CAL) != 0
    }

    /**
     * Check if the busy bit is set in the status byte.
     *
     * @return true if a measurement is in progress
     */
    boolean isBusy() {
        (readStatus() & STATUS_BUSY) != 0
    }

    /**
     * Send the soft reset command, wait 20 ms, and re-run the init command.
     */
    void softReset() {
        connection.write(CMD_SOFT_RESET)
        Thread.sleep(20)
        initialize()
    }

    /**
     * Trigger a measurement and return the dew point (Magnus formula).
     *
     * @return dew point in °C; -50.0 if humidity is 0 %RH
     */
    double dewPoint() {
        double[] m = read()
        double t = m[0]
        double rh = m[1]
        if (rh <= 0.0d) return -50.0d
        double gamma = Math.log(rh / 100.0d) + 17.62d * t / (243.12d + t)
        243.12d * gamma / (17.62d - gamma)
    }
}
