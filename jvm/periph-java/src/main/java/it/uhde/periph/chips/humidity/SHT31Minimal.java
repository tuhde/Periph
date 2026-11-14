package it.uhde.periph.chips.humidity;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * SHT31 — digital temperature and humidity sensor (Sensirion), minimal driver.
 *
 * <p>Each {@link #read()} runs one single-shot measurement at high repeatability with clock
 * stretching disabled and CRC checking on. The heater stays off. I²C address 0x44 (ADDR low) or
 * 0x45 (ADDR high).
 *
 * <h2>Configuration defaults</h2>
 * <ul>
 *   <li>Soft reset (0x30A2) at construction with 1 ms waits before and after</li>
 *   <li>Single shot, high repeatability (0x2400), fixed 15 ms wait; if the read header is still
 *       NACKed the driver keeps polling for up to 20 ms more</li>
 *   <li>Both CRC-8 bytes verified; one retry of the whole measurement on a mismatch, then an
 *       {@link IOException}</li>
 * </ul>
 */
public class SHT31Minimal {

    protected static final int CMD_SOFT_RESET   = 0x30A2;
    protected static final int CMD_HEATER_ON    = 0x306D;
    protected static final int CMD_HEATER_OFF   = 0x3066;
    protected static final int CMD_READ_STATUS  = 0xF32D;
    protected static final int CMD_CLEAR_STATUS = 0x3041;
    protected static final int CMD_FETCH        = 0xE000;
    protected static final int CMD_BREAK        = 0x3093;
    protected static final int CMD_ART          = 0x2B32;

    /** Single-shot commands without clock stretching, indexed low / medium / high. */
    protected static final int[] SINGLE_NO_STRETCH = {0x2416, 0x240B, 0x2400};
    /** Single-shot commands with clock stretching, indexed low / medium / high. */
    protected static final int[] SINGLE_STRETCH = {0x2C10, 0x2C0D, 0x2C06};
    /** Conversion wait in ms, indexed low / medium / high. */
    protected static final int[] SINGLE_WAIT_MS = {4, 6, 15};

    protected final Connection connection;
    protected int level = 2;          // 0 low, 1 medium, 2 high
    protected boolean stretch = false;

    /**
     * Soft-reset the sensor.
     *
     * @param connection I²C connection bound to 0x44 or 0x45
     * @throws IOException on bus error
     */
    public SHT31Minimal(Connection connection) throws IOException {
        this.connection = connection;
        sleepMs(1);
        command(CMD_SOFT_RESET);
        sleepMs(1);
    }

    protected static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * CRC-8 used by the SHT3x: polynomial 0x31, init 0xFF, no reflection, no final XOR.
     *
     * @param data bytes to check
     * @param off  first byte
     * @param len  number of bytes
     * @return the CRC
     */
    public static int crc8(byte[] data, int off, int len) {
        int crc = 0xFF;
        for (int i = off; i < off + len; i++) {
            crc ^= data[i] & 0xFF;
            for (int b = 0; b < 8; b++) {
                crc = (crc & 0x80) != 0 ? ((crc << 1) ^ 0x31) & 0xFF : (crc << 1) & 0xFF;
            }
        }
        return crc;
    }

    protected void command(int code) throws IOException {
        connection.write(new byte[]{(byte) (code >> 8), (byte) code});
    }

    /** Verify both CRCs of a 6-byte result and return the raw {temperature, humidity} words, or null on a mismatch. */
    protected static int[] decode(byte[] d) {
        if (crc8(d, 0, 2) != (d[2] & 0xFF) || crc8(d, 3, 2) != (d[5] & 0xFF)) return null;
        return new int[]{((d[0] & 0xFF) << 8) | (d[1] & 0xFF), ((d[3] & 0xFF) << 8) | (d[4] & 0xFF)};
    }

    protected static double[] convert(int rawT, int rawRh) {
        return new double[]{-45.0 + 175.0 * rawT / 65535.0, 100.0 * rawRh / 65535.0};
    }

    /** One single-shot measurement; returns the raw {temperature, humidity} words. */
    protected int[] singleShot() throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            command(stretch ? SINGLE_STRETCH[level] : SINGLE_NO_STRETCH[level]);
            if (!stretch) sleepMs(SINGLE_WAIT_MS[level]);
            byte[] data = null;
            for (int i = 0; i < 21 && data == null; i++) {
                try {
                    data = connection.read(6);
                } catch (IOException nack) {
                    sleepMs(1);                       // read header NACKed: not ready yet
                }
            }
            if (data == null) throw new IOException("SHT31 measurement did not complete");
            int[] raw = decode(data);
            if (raw != null) return raw;
        }
        throw new IOException("SHT31 CRC mismatch");
    }

    /**
     * Run one single-shot measurement.
     *
     * @return {temperature in °C, relative humidity in %RH}
     * @throws IOException on bus error, timeout or a CRC mismatch that persists after one retry
     */
    public double[] read() throws IOException {
        int[] raw = singleShot();
        return convert(raw[0], raw[1]);
    }
}
