package it.uhde.periph.chips.environmental

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/**
 * AHT20 — full driver. Extends {@link Aht20Minimal} with individual readings,
 * CRC, raw values, status inspection, explicit soft reset and dew point.
 */
@CompileStatic
class Aht20Full extends Aht20Minimal {

    /**
     * Construct the full driver and perform power-on initialization.
     *
     * @param connection I²C connection bound to the AHT20 device address (0x38)
     */
    Aht20Full(Connection connection) {
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
     * Trigger a measurement, read 7 bytes and verify the CRC-8 (polynomial 0x31,
     * initial value 0xFF, over bytes 0 to 5, compared with byte 6).
     *
     * @return double array: [0] = temperature in °C, [1] = humidity in %RH, [2] = 1.0 if the CRC is ok else 0.0
     */
    double[] readWithCrc() {
        byte[] data = measure(7)
        int[] raw = decodeRaw(data)
        double humidityPct  = Math.min(Math.max((raw[0] / 1048576.0d) * 100.0d, 0.0d), 100.0d)
        double temperatureC = (raw[1] / 1048576.0d) * 200.0d - 50.0d
        boolean crcOk = crc8(data, 6) == (data[6] & 0xFF)
        [temperatureC, humidityPct, crcOk ? 1.0d : 0.0d] as double[]
    }

    /** CRC-8 (polynomial 0x31, initial value 0xFF) over the first {@code length} bytes. */
    protected static int crc8(byte[] data, int length) {
        int crc = 0xFF
        for (int i = 0; i < length; i++) {
            crc ^= data[i] & 0xFF
            for (int b = 0; b < 8; b++) {
                crc = ((crc & 0x80) != 0) ? ((crc << 1) ^ 0x31) & 0xFF : (crc << 1) & 0xFF
            }
        }
        crc
    }

    /**
     * Read the status byte.
     *
     * @return status byte (bit 7 BUSY, bit 3 CAL)
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
     * Send the soft reset command, wait 20 ms, and re-run the calibration check.
     */
    void softReset() {
        connection.write(CMD_SOFT_RESET)
        Thread.sleep(20)
        calibrate()
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
