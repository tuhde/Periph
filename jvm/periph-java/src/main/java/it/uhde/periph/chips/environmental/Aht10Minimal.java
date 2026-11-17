package it.uhde.periph.chips.environmental;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * AHT10 — temperature and humidity sensor with I²C interface (minimal driver).
 *
 * <p>Measures temperature (-50 to 150 °C) and relative humidity (0 to 100 %RH).
 * Handles power-on wait, initialization (0xE1 calibration enable), calibration
 * check and measurement triggering automatically.
 *
 * <p>Default I²C address: 0x38.
 *
 * <h2>Configuration defaults</h2>
 * <ul>
 *   <li>One-shot measurement triggered on every read() call</li>
 *   <li>80 ms wait after trigger, then BUSY polling (10 ms steps, 200 ms guard)</li>
 *   <li>No CRC (the AHT10 does not send one)</li>
 * </ul>
 */
public class Aht10Minimal {

    protected static final byte[] CMD_INIT       = { (byte) 0xE1, (byte) 0x08, (byte) 0x00 };
    protected static final byte[] CMD_TRIGGER    = { (byte) 0xAC, (byte) 0x33, (byte) 0x00 };
    protected static final byte[] CMD_SOFT_RESET = { (byte) 0xBA };

    protected static final int STATUS_BUSY = 0x80;
    protected static final int STATUS_CAL  = 0x08;

    protected final Connection connection;

    /**
     * Construct the driver and perform power-on initialization.
     *
     * <p>Waits 40 ms, sends the init command, and checks the CAL bit. If it is
     * clear, sends a soft reset and repeats the init command once.
     *
     * @param connection I²C connection bound to the AHT10 device address (0x38)
     * @throws IOException on I²C error, or if the sensor is still uncalibrated after the retry
     */
    public Aht10Minimal(Connection connection) throws IOException {
        this.connection = connection;
        sleep(40);
        initialize();
        if ((readStatus() & STATUS_CAL) == 0) {
            connection.write(CMD_SOFT_RESET);
            sleep(20);
            initialize();
            if ((readStatus() & STATUS_CAL) == 0) {
                throw new IOException("AHT10 not calibrated after init");
            }
        }
    }

    /**
     * Trigger a measurement and return temperature and humidity.
     *
     * <p>Sends the trigger command, waits 80 ms, polls BUSY, reads 6 bytes, and
     * decodes the raw 20-bit values into physical units.
     *
     * @return double array: [0] = temperature in °C, [1] = humidity in %RH
     * @throws IOException on I²C error or if BUSY does not clear within 200 ms
     */
    public double[] read() throws IOException {
        int[] raw = decodeRaw(measure());
        double humidityPct  = Math.min(Math.max((raw[0] / 1048576.0) * 100.0, 0.0), 100.0);
        double temperatureC = (raw[1] / 1048576.0) * 200.0 - 50.0;
        return new double[]{ temperatureC, humidityPct };
    }

    /** Send the init (calibration enable) command and wait 10 ms. */
    protected void initialize() throws IOException {
        connection.write(CMD_INIT);
        sleep(10);
    }

    protected int readStatus() throws IOException {
        byte[] buf = connection.read(1);
        return buf[0] & 0xFF;
    }

    /** Trigger a conversion and return the 6-byte frame once BUSY is clear. */
    protected byte[] measure() throws IOException {
        connection.write(CMD_TRIGGER);
        sleep(80); // conversion takes > 75 ms
        for (int waited = 0; ; waited += 10) {
            byte[] data = connection.read(6);
            if ((data[0] & STATUS_BUSY) == 0) return data;
            if (waited >= 200) throw new IOException("AHT10 measurement timeout");
            sleep(10);
        }
    }

    /** Extract {rawHumidity, rawTemperature} (20 bit each) from a response frame. */
    protected static int[] decodeRaw(byte[] buf) {
        int rawRh = ((buf[1] & 0xFF) << 12) | ((buf[2] & 0xFF) << 4) | ((buf[3] & 0xFF) >> 4);
        int rawT  = ((buf[3] & 0x0F) << 16) | ((buf[4] & 0xFF) << 8) | (buf[5] & 0xFF);
        return new int[]{ rawRh, rawT };
    }

    protected static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
