package it.uhde.periph.chips.humidity;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * HS40xx — relative humidity and temperature sensors (Renesas HS4001, HS4003), minimal driver.
 *
 * <p>HS4001 and HS4003 share one protocol, so {@link HS4001Minimal} and {@link HS4003Minimal} are
 * thin subclasses (HS4002 and the HS401x variants differ only physically and get no class).
 * Each {@link #read()} sends the no-hold humidity + temperature command (0xF5), waits 3 ms,
 * reads five bytes (retrying while the sensor NACKs the read header, for up to 20 ms), verifies
 * the CRC-8 and converts. A CRC mismatch is retried once. I²C address 0x54 (fixed).
 *
 * <h2>Configuration defaults</h2>
 * <ul>
 *   <li>Nothing is written at construction</li>
 *   <li>Power-on state: 14-bit resolution, one-shot no-hold measurements, CRC checked,
 *       periodic mode and alerts off</li>
 * </ul>
 */
public class HS40xxMinimal {

    protected static final double FULL_SCALE = 16383.0;
    protected static final int CMD_T_NOHOLD = 0xF3;
    protected static final int CMD_HT_HOLD = 0xE5;
    protected static final int CMD_HT_NOHOLD = 0xF5;

    protected final Connection connection;

    /**
     * Create the driver; does no bus access.
     *
     * @param connection I²C connection bound to 0x54
     */
    public HS40xxMinimal(Connection connection) {
        this.connection = connection;
    }

    protected static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** CRC-8 (polynomial 0x1D, initial value 0xFF, no reflection, no final XOR). */
    protected static int crc8(byte[] data) {
        int crc = 0xFF;
        for (byte b : data) {
            crc ^= b & 0xFF;
            for (int i = 0; i < 8; i++) {
                crc = (crc & 0x80) != 0 ? ((crc << 1) ^ 0x1D) & 0xFF : (crc << 1) & 0xFF;
            }
        }
        return crc;
    }

    /** True if the last byte of {@code frame} is the CRC of its data (3-byte frames: humidity bytes taken as zero). */
    protected static boolean crcOk(byte[] f) {
        byte[] data = f.length == 3 ? new byte[]{0, 0, f[0], f[1]} : new byte[]{f[0], f[1], f[2], f[3]};
        return crc8(data) == (f[f.length - 1] & 0xFF);
    }

    /** Read {@code len} bytes, retrying while the sensor NACKs the read header (up to 20 ms). */
    protected byte[] readFrame(int len) throws IOException {
        IOException last = null;
        for (int i = 0; i < 21; i++) {
            try {
                return connection.read(len);
            } catch (IOException e) {
                last = e;
                sleepMs(1);
            }
        }
        throw last;
    }

    /** Send {@code cmd}, wait (no-hold), read {@code len} bytes and verify the CRC; the sequence is retried once on a mismatch. */
    protected byte[] measure(int cmd, int len, boolean hold) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            connection.write(new byte[]{(byte) cmd});
            if (!hold) sleepMs(3);
            byte[] f = readFrame(len);
            if (crcOk(f)) return f;
        }
        throw new IOException("HS40xx CRC mismatch");
    }

    protected static int rawHumidity(byte[] d) {
        return ((d[0] & 0x3F) << 8) | (d[1] & 0xFF);
    }

    protected static int rawTemperature(byte[] d, int offset) {
        return ((d[offset] & 0x3F) << 8) | (d[offset + 1] & 0xFF);
    }

    protected static double toHumidity(int raw) {
        return raw / FULL_SCALE * 100.0;
    }

    protected static double toTemperature(int raw) {
        return raw / FULL_SCALE * 165.0 - 40.0;
    }

    /**
     * Measure temperature and humidity (no-hold {@code 0xF5}, CRC verified).
     *
     * @return {temperature in °C (−40…125), relative humidity in %RH (0…100)}
     * @throws IOException on bus error, a read NACKed for more than 20 ms, or a CRC mismatch after one retry
     */
    public double[] read() throws IOException {
        byte[] d = measure(CMD_HT_NOHOLD, 5, false);
        return new double[]{toTemperature(rawTemperature(d, 2)), toHumidity(rawHumidity(d))};
    }
}
