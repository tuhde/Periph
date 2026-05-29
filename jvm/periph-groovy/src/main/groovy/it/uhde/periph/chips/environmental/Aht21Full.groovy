package it.uhde.periph.chips.environmental

import groovy.transform.CompileStatic
import it.uhde.periph.transport.Transport

/**
 * AHT21 — full driver. Extends {@link Aht21Minimal} with CRC verification,
 * explicit soft reset, calibration status inspection, and individual
 * temperature/humidity readings.
 */
@CompileStatic
class Aht21Full extends Aht21Minimal {

    /**
     * Construct the full driver and perform power-on initialization.
     *
     * @param transport I²C transport bound to the AHT21 device address (0x38)
     */
    Aht21Full(Transport transport) {
        super(transport)
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
     * Trigger a measurement, read 7 bytes, and verify CRC-8.
     *
     * <p>Uses polynomial x^8 + x^5 + x^4 + 1 (0x31) with initial value 0xFF
     * to verify the CRC byte against bytes 0–5 of the response.
     *
     * @return double array: [0] = temperature in °C, [1] = humidity in %RH, [2] = 1.0 if CRC ok, 0.0 if not
     */
    double[] readWithCrc() {
        transport.write(CMD_TRIGGER)
        Thread.sleep(80)
        byte[] data = transport.read(7)
        double[] result = decode(data)
        boolean crcOk = crc8(data, 6) == (data[6] & 0xFF)
        [result[0], result[1], crcOk ? 1.0d : 0.0d] as double[]
    }

    /**
     * Send the soft reset command and wait 20 ms for recovery.
     */
    void softReset() {
        transport.write(CMD_SOFT_RESET)
        Thread.sleep(20)
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

    private static int crc8(byte[] data, int len) {
        int crc = 0xFF
        for (int i = 0; i < len; i++) {
            crc ^= (data[i] & 0xFF)
            for (int j = 0; j < 8; j++) {
                if ((crc & 0x80) != 0)
                    crc = ((crc << 1) ^ 0x31) & 0xFF
                else
                    crc = (crc << 1) & 0xFF
            }
        }
        crc
    }
}
