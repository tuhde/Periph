package it.uhde.periph.chips.humidity;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * SHTC3 — relative humidity and temperature sensor (Sensirion), minimal driver.
 *
 * <p>Each {@link #read()} runs a full measurement cycle: wake-up (0x3517), a normal-mode
 * temperature-first measurement without clock stretching (0x7866), a 13 ms wait, a 6-byte read
 * with both CRC-8 bytes verified, and the sleep command (0xB098). A bad CRC is retried once.
 * I²C address 0x70. The chip is command driven (no register pointer), so a plain
 * {@link Connection} is used.
 *
 * <h2>Configuration defaults</h2>
 * <ul>
 *   <li>Nothing is written at construction; {@link #init()} performs the soft reset and the ID check</li>
 *   <li>Normal mode, no clock stretching, the sensor sleeps after every reading</li>
 *   <li>Relative humidity is clamped to 0–100 %RH; both CRCs are verified</li>
 * </ul>
 */
public class Shtc3Minimal {

    protected static final int CMD_SLEEP   = 0xB098;
    protected static final int CMD_WAKE    = 0x3517;
    protected static final int CMD_RESET   = 0x805D;
    protected static final int CMD_READ_ID = 0xEFC8;
    /** Measurement commands [lowPower][clockStretching], temperature first. */
    protected static final int[][] CMD_MEASURE = {{0x7866, 0x7CA2}, {0x609C, 0x6458}};
    protected static final int ID_MASK  = 0x083F;
    protected static final int ID_VALUE = 0x0807;

    protected final Connection connection;
    protected boolean lowPower = false;
    protected boolean clockStretching = false;
    protected boolean sleepBetween = true;
    protected boolean asleep = false;

    /**
     * Create the driver; does no bus access.
     *
     * @param connection I²C connection bound to 0x70
     */
    public Shtc3Minimal(Connection connection) {
        this.connection = connection;
    }

    protected static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * CRC-8 used by the SHTC3: polynomial 0x31 (x^8 + x^5 + x^4 + 1), init 0xFF, no final XOR.
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

    protected void wakeIfAsleep() throws IOException {
        if (asleep) {
            command(CMD_WAKE);
            sleepMs(1);
            asleep = false;
        }
    }

    protected void goToSleep() throws IOException {
        command(CMD_SLEEP);
        asleep = true;
    }

    /** CRC-verified 16-bit word at {@code off}. */
    protected static int word(byte[] data, int off) throws IOException {
        if (crc8(data, off, 2) != (data[off + 2] & 0xFF)) {
            throw new IOException("SHTC3 CRC mismatch");
        }
        return ((data[off] & 0xFF) << 8) | (data[off + 1] & 0xFF);
    }

    protected int readIdWord() throws IOException {
        command(CMD_READ_ID);
        return word(connection.read(3), 0);
    }

    /** One measurement cycle; returns {t_raw, rh_raw}. A bad CRC is retried once. */
    protected int[] measure() throws IOException {
        wakeIfAsleep();
        try {
            return measureOnce();
        } finally {
            if (sleepBetween) goToSleep();
        }
    }

    private int[] measureOnce() throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            command(CMD_MEASURE[lowPower ? 1 : 0][clockStretching ? 1 : 0]);
            if (!clockStretching) sleepMs(lowPower ? 1 : 13);
            byte[] data = connection.read(6);
            try {
                return new int[]{word(data, 0), word(data, 3)};
            } catch (IOException e) {
                last = e;
            }
        }
        throw last;
    }

    protected static double toHumidity(int code) {
        return Math.min(100.0, Math.max(0.0, 100.0 * code / 65536.0));
    }

    protected static double toTemperature(int code) {
        return -45.0 + 175.0 * code / 65536.0;
    }

    /**
     * Wake, soft reset (0x805D), check the product ID, then sleep.
     *
     * @throws IOException on bus error, CRC mismatch or a wrong product code
     */
    public void init() throws IOException {
        asleep = true;
        wakeIfAsleep();
        command(CMD_RESET);
        sleepMs(1);
        int id;
        try {
            id = readIdWord();
        } finally {
            goToSleep();
        }
        if ((id & ID_MASK) != ID_VALUE) {
            throw new IOException(String.format("not an SHTC3 (ID 0x%04X)", id));
        }
    }

    /**
     * Measure temperature and humidity.
     *
     * @return {temperature °C, relative humidity %RH (0–100)}
     * @throws IOException on bus error or CRC mismatch
     */
    public double[] read() throws IOException {
        int[] w = measure();
        return new double[]{toTemperature(w[0]), toHumidity(w[1])};
    }
}
