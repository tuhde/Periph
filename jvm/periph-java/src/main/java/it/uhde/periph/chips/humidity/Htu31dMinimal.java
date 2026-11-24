package it.uhde.periph.chips.humidity;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * HTU31D — relative humidity and temperature sensor (TE Connectivity), minimal driver.
 *
 * <p>Each {@link #read()} starts one conversion at the highest resolution (command 0x5E, OSR 3
 * for humidity and temperature), waits 25 ms, reads the temperature and humidity words (command
 * 0x00) and verifies both CRC-8 bytes. I²C address 0x40 or 0x41 ({@code IC_Add} pin). The chip is
 * command driven (no register pointer), so a plain {@link Connection} is used.
 *
 * <h2>Configuration defaults</h2>
 * <ul>
 *   <li>Nothing is written at construction; {@link #init()} performs the soft reset</li>
 *   <li>OSR 3 for humidity and temperature, heater off</li>
 *   <li>Relative humidity is clamped to 0–100 %RH; both CRCs are verified</li>
 * </ul>
 */
public class Htu31dMinimal {

    protected static final int CMD_RESET       = 0x1E;
    protected static final int CMD_HEATER_ON   = 0x04;
    protected static final int CMD_HEATER_OFF  = 0x02;
    protected static final int CMD_CONVERT     = 0x40;
    protected static final int CMD_READ_TRH    = 0x00;
    protected static final int CMD_READ_RH     = 0x10;
    protected static final int CMD_READ_DIAG   = 0x08;
    protected static final int CMD_READ_SERIAL = 0x0A;

    /** Typical humidity conversion time in seconds for OSR 0..3. */
    protected static final double[] T_RH = {0.00111, 0.00214, 0.00421, 0.00834};
    /** Typical temperature conversion time in seconds for OSR 0..3. */
    protected static final double[] T_T = {0.00157, 0.00306, 0.00603, 0.01198};

    protected final Connection connection;
    protected int osrRh = 3;
    protected int osrT = 3;

    /**
     * Create the driver; does no bus access.
     *
     * @param connection I²C connection bound to 0x40 or 0x41
     */
    public Htu31dMinimal(Connection connection) {
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
     * CRC-8 used by the HTU31D: polynomial 0x31 (x^8 + x^5 + x^4 + 1), init 0x00, no final XOR.
     *
     * @param data bytes to check
     * @param off  first byte
     * @param len  number of bytes
     * @return the CRC
     */
    public static int crc8(byte[] data, int off, int len) {
        int crc = 0x00;
        for (int i = off; i < off + len; i++) {
            crc ^= data[i] & 0xFF;
            for (int b = 0; b < 8; b++) {
                crc = (crc & 0x80) != 0 ? ((crc << 1) ^ 0x31) & 0xFF : (crc << 1) & 0xFF;
            }
        }
        return crc;
    }

    protected void command(int code) throws IOException {
        connection.write(new byte[]{(byte) code});
    }

    /**
     * Soft reset (0x1E) and wait 15 ms; heater off, highest resolution.
     *
     * @throws IOException on bus error
     */
    public void init() throws IOException {
        command(CMD_RESET);
        sleepMs(15);
    }

    protected void start() throws IOException {
        command(CMD_CONVERT | (osrRh << 3) | (osrT << 1));
    }

    protected long waitMs() {
        if (osrRh == 3 && osrT == 3) return 25;
        return (long) Math.ceil(1.25 * (T_RH[osrRh] + T_T[osrT]) * 1000.0);
    }

    /** Write a read command and return the CRC-verified 16-bit words. */
    protected int[] readWords(int command, int count) throws IOException {
        command(command);
        byte[] data = connection.read(3 * count);
        int[] words = new int[count];
        for (int i = 0; i < count; i++) {
            if (crc8(data, 3 * i, 2) != (data[3 * i + 2] & 0xFF)) {
                throw new IOException("HTU31D CRC mismatch");
            }
            words[i] = ((data[3 * i] & 0xFF) << 8) | (data[3 * i + 1] & 0xFF);
        }
        return words;
    }

    protected int[] measure(int command, int count) throws IOException {
        start();
        sleepMs(waitMs());
        return readWords(command, count);
    }

    protected static double toHumidity(int code) {
        return Math.min(100.0, Math.max(0.0, 100.0 * code / 65535.0));
    }

    protected static double toTemperature(int code) {
        return -40.0 + 165.0 * code / 65535.0;
    }

    /**
     * Measure temperature and humidity.
     *
     * @return {temperature °C, relative humidity %RH (0–100)}
     * @throws IOException on bus error or CRC mismatch
     */
    public double[] read() throws IOException {
        int[] w = measure(CMD_READ_TRH, 2);
        return new double[]{toTemperature(w[0]), toHumidity(w[1])};
    }
}
