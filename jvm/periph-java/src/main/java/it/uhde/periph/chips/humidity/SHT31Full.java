package it.uhde.periph.chips.humidity;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * SHT31 — full driver extending {@link SHT31Minimal}: single values and raw words, the three
 * repeatability levels, the clock-stretching command set, periodic measurements (0.5–10 mps plus
 * ART), the heater, the status register, soft reset and the dew point.
 */
public class SHT31Full extends SHT31Minimal {

    /** Periodic command codes: rows 0.5 / 1 / 2 / 4 / 10 mps, columns low / medium / high. */
    private static final int[][] PERIODIC = {
        {0x202F, 0x2024, 0x2032},
        {0x212D, 0x2126, 0x2130},
        {0x222B, 0x2220, 0x2236},
        {0x2329, 0x2322, 0x2334},
        {0x272A, 0x2721, 0x2737},
    };

    /**
     * Construct the full driver (soft-resets the sensor).
     *
     * @param connection I²C connection bound to 0x44 or 0x45
     * @throws IOException on bus error
     */
    public SHT31Full(Connection connection) throws IOException {
        super(connection);
    }

    private static int levelIndex(String name) {
        switch (name) {
            case "low": return 0;
            case "medium": return 1;
            case "high": return 2;
            default: throw new IllegalArgumentException("level must be 'low', 'medium' or 'high'");
        }
    }

    /**
     * Run one measurement and return the temperature only.
     *
     * @return temperature in °C
     * @throws IOException on bus error
     */
    public double readTemperature() throws IOException { return read()[0]; }

    /**
     * Run one measurement and return the relative humidity only.
     *
     * @return relative humidity in %RH
     * @throws IOException on bus error
     */
    public double readHumidity() throws IOException { return read()[1]; }

    /**
     * Run one measurement and return the raw 16-bit words.
     *
     * @return {raw temperature, raw humidity}, each 0–65535
     * @throws IOException on bus error
     */
    public int[] readRaw() throws IOException { return singleShot(); }

    /**
     * Select the repeatability for later single-shot reads (measurement time 4 / 6 / 15 ms).
     *
     * @param repeatability "low", "medium" or "high"
     * @throws IllegalArgumentException for any other value
     */
    public void setRepeatability(String repeatability) { this.level = levelIndex(repeatability); }

    /**
     * Use the clock-stretching command set for single-shot reads (the bus layer must support it);
     * the fixed wait is skipped.
     *
     * @param on true to enable
     */
    public void setClockStretching(boolean on) { this.stretch = on; }

    /**
     * Start periodic measurements; read results with {@link #fetch()}.
     *
     * @param rate          measurements per second: 0.5, 1, 2, 4 or 10 (ignored with {@code art})
     * @param repeatability "low", "medium" or "high"
     * @param art           true for the accelerated response time mode (4 mps, command 0x2B32)
     * @throws IOException on bus error
     * @throws IllegalArgumentException for an unsupported rate or repeatability
     */
    public void startPeriodic(double rate, String repeatability, boolean art) throws IOException {
        if (art) {
            command(CMD_ART);
            return;
        }
        int row;
        if (rate == 0.5) row = 0;
        else if (rate == 1.0) row = 1;
        else if (rate == 2.0) row = 2;
        else if (rate == 4.0) row = 3;
        else if (rate == 10.0) row = 4;
        else throw new IllegalArgumentException("rate must be 0.5, 1, 2, 4 or 10");
        command(PERIODIC[row][levelIndex(repeatability)]);
    }

    /**
     * Read the latest periodic result (command 0xE000).
     *
     * @return {temperature in °C, relative humidity in %RH}, or {@code null} if no new data is
     *         available (the read header is NACKed)
     * @throws IOException on bus error or a CRC mismatch
     */
    public double[] fetch() throws IOException {
        command(CMD_FETCH);
        byte[] data;
        try {
            data = connection.read(6);
        } catch (IOException nack) {
            return null;
        }
        int[] raw = decode(data);
        if (raw == null) throw new IOException("SHT31 CRC mismatch");
        return convert(raw[0], raw[1]);
    }

    /**
     * Stop periodic mode (break command 0x3093) and wait 1 ms.
     *
     * @throws IOException on bus error
     */
    public void stopPeriodic() throws IOException {
        command(CMD_BREAK);
        sleepMs(1);
    }

    /**
     * Switch the on-chip heater (about 5–25 mW) on or off.
     *
     * @param on true for on
     * @throws IOException on bus error
     */
    public void setHeater(boolean on) throws IOException {
        command(on ? CMD_HEATER_ON : CMD_HEATER_OFF);
    }

    /**
     * Read the status register (command 0xF32D, CRC verified).
     *
     * @return 16-bit status, e.g. 0x8010 after reset; bit 13 = heater on
     * @throws IOException on bus error or a CRC mismatch
     */
    public int readStatus() throws IOException {
        command(CMD_READ_STATUS);
        byte[] d = connection.read(3);
        if (crc8(d, 0, 2) != (d[2] & 0xFF)) throw new IOException("SHT31 CRC mismatch");
        return ((d[0] & 0xFF) << 8) | (d[1] & 0xFF);
    }

    /**
     * Clear the status register flags (command 0x3041).
     *
     * @throws IOException on bus error
     */
    public void clearStatus() throws IOException { command(CMD_CLEAR_STATUS); }

    /**
     * Soft reset (0x30A2), wait 1 ms; leaves periodic mode and returns to single shot.
     *
     * @throws IOException on bus error
     */
    public void softReset() throws IOException {
        command(CMD_SOFT_RESET);
        sleepMs(1);
    }

    /**
     * Run one measurement and compute the dew point (Magnus formula).
     *
     * @return dew point in °C
     * @throws IOException on bus error
     */
    public double dewPoint() throws IOException {
        double[] v = read();
        double t = v[0];
        double rh = v[1] <= 0.0 ? 0.01 : v[1];
        double gamma = Math.log(rh / 100.0) + (17.62 * t) / (243.12 + t);
        return 243.12 * gamma / (17.62 - gamma);
    }
}
