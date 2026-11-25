package it.uhde.periph.chips.humidity;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * Si7021 — relative humidity and temperature sensor (Silicon Labs), minimal driver.
 *
 * <p>Each {@link #read()} runs one relative-humidity measurement without hold master (the read
 * header is polled until the chip ACKs, timeout 50 ms), verifies the CRC and then fetches the
 * temperature of that same measurement with command 0xE0. I²C address 0x40 (fixed).
 *
 * <h2>Configuration defaults</h2>
 * <ul>
 *   <li>Nothing is written at construction; power-on defaults: 12-bit RH / 14-bit temperature,
 *       heater off</li>
 *   <li>No-hold-master measurement (0xF5), polling the NACKed read header</li>
 *   <li>CRC-8 verified on the RH result; relative humidity clamped to 0–100 %RH</li>
 * </ul>
 */
public class Si7021Minimal {

    protected static final int CMD_RH_HOLD     = 0xE5;
    protected static final int CMD_RH_POLL     = 0xF5;
    protected static final int CMD_T_HOLD      = 0xE3;
    protected static final int CMD_T_POLL      = 0xF3;
    protected static final int CMD_T_PREVIOUS  = 0xE0;
    protected static final int CMD_RESET       = 0xFE;
    protected static final int CMD_WRITE_USER  = 0xE6;
    protected static final int CMD_READ_USER   = 0xE7;
    protected static final int POLL_ATTEMPTS   = 50;

    protected final Connection connection;
    protected boolean stretch = false;

    /**
     * Create the driver; does no bus access.
     *
     * @param connection I²C connection bound to 0x40
     */
    public Si7021Minimal(Connection connection) {
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
     * CRC-8 used by the Si7021: polynomial 0x31, init 0x00, no reflection, no final XOR.
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

    /** Run one measurement command and return the CRC-verified raw 16-bit code. */
    protected int measure(int holdCmd, int pollCmd) throws IOException {
        byte[] data = null;
        if (stretch) {
            command(holdCmd);
            data = connection.read(3);
        } else {
            command(pollCmd);
            for (int i = 0; i < POLL_ATTEMPTS && data == null; i++) {
                try {
                    data = connection.read(3);
                } catch (IOException nack) {
                    sleepMs(1);                       // read header NACKed: not ready yet
                }
            }
            if (data == null) throw new IOException("Si7021 measurement did not complete");
        }
        if (crc8(data, 0, 2) != (data[2] & 0xFF)) throw new IOException("Si7021 CRC mismatch");
        return ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
    }

    protected int previousCode() throws IOException {
        command(CMD_T_PREVIOUS);
        byte[] d = connection.read(2);
        return ((d[0] & 0xFF) << 8) | (d[1] & 0xFF);
    }

    protected static double toHumidity(int code) {
        return Math.min(100.0, Math.max(0.0, 125.0 * code / 65536.0 - 6.0));
    }

    protected static double toTemperature(int code) {
        return 175.72 * code / 65536.0 - 46.85;
    }

    /**
     * Run one RH measurement and read the temperature of the same conversion.
     *
     * @return {temperature in °C, relative humidity in %RH (0–100)}
     * @throws IOException on bus error, timeout (50 ms) or a CRC mismatch
     */
    public double[] read() throws IOException {
        int rh = measure(CMD_RH_HOLD, CMD_RH_POLL);
        int t = previousCode();
        return new double[]{toTemperature(t), toHumidity(rh)};
    }
}
