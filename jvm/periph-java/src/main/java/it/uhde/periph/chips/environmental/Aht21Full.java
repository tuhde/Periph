package it.uhde.periph.chips.environmental;

import it.uhde.periph.transport.Transport;

import java.io.IOException;

/**
 * AHT21 — full driver. Extends {@link Aht21Minimal} with CRC verification,
 * explicit soft reset, calibration status inspection, and individual
 * temperature/humidity readings.
 */
public class Aht21Full extends Aht21Minimal {

    /**
     * Construct the full driver and perform power-on initialization.
     *
     * @param transport I²C transport bound to the AHT21 device address (0x38)
     * @throws IOException on I²C error
     */
    public Aht21Full(Transport transport) throws IOException {
        super(transport);
    }

    /**
     * Trigger a measurement and return temperature only.
     *
     * @return temperature in °C (-50 to 150 °C)
     * @throws IOException on I²C error
     */
    public double readTemperature() throws IOException {
        return read()[0];
    }

    /**
     * Trigger a measurement and return humidity only.
     *
     * @return relative humidity in %RH (0 to 100 %RH)
     * @throws IOException on I²C error
     */
    public double readHumidity() throws IOException {
        return read()[1];
    }

    /**
     * Trigger a measurement, read 7 bytes, and verify CRC-8.
     *
     * <p>Uses polynomial x^8 + x^5 + x^4 + 1 (0x31) with initial value 0xFF
     * to verify the CRC byte against bytes 0–5 of the response.
     *
     * @return double array: [0] = temperature in °C, [1] = humidity in %RH, [2] = 1.0 if CRC ok, 0.0 if not
     * @throws IOException on I²C error
     */
    public double[] readWithCrc() throws IOException {
        transport.write(CMD_TRIGGER);
        sleep(80);
        byte[] data = transport.read(7);
        double[] result = decode(data);
        boolean crcOk = crc8(data, 6) == (data[6] & 0xFF);
        return new double[]{ result[0], result[1], crcOk ? 1.0 : 0.0 };
    }

    /**
     * Send the soft reset command and wait 20 ms for recovery.
     *
     * @throws IOException on I²C error
     */
    public void softReset() throws IOException {
        transport.write(CMD_SOFT_RESET);
        sleep(20);
    }

    /**
     * Check if the calibration bit is set in the status byte.
     *
     * @return true if the sensor reports calibration enabled
     * @throws IOException on I²C error
     */
    public boolean isCalibrated() throws IOException {
        return (readStatus() & STATUS_CAL) != 0;
    }

    /**
     * Check if the busy bit is set in the status byte.
     *
     * @return true if a measurement is in progress
     * @throws IOException on I²C error
     */
    public boolean isBusy() throws IOException {
        return (readStatus() & STATUS_BUSY) != 0;
    }

    private static int crc8(byte[] data, int len) {
        int crc = 0xFF;
        for (int i = 0; i < len; i++) {
            crc ^= (data[i] & 0xFF);
            for (int j = 0; j < 8; j++) {
                if ((crc & 0x80) != 0)
                    crc = ((crc << 1) ^ 0x31) & 0xFF;
                else
                    crc = (crc << 1) & 0xFF;
            }
        }
        return crc;
    }
}
