package it.uhde.periph.chips.humidity;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * HTU21D-F — relative humidity and temperature sensor (Measurement Specialties), minimal driver.
 *
 * <p>Each {@link #read()} triggers one temperature (0xF3) and one humidity (0xF5) measurement
 * without hold master, polls the NACKed read header until the chip ACKs (timeout 100 ms) and
 * verifies the CRC of both results (one retry on a mismatch). I²C address 0x40 (fixed).
 *
 * <h2>Configuration defaults</h2>
 * <ul>
 *   <li>Nothing is written at construction; {@link #init()} performs the soft reset</li>
 *   <li>Power-on defaults: 12-bit RH / 14-bit temperature, heater off</li>
 *   <li>No-hold-master measurement, polling the NACKed read header; CRC-8 verified</li>
 *   <li>Relative humidity is not clamped (ASIC range −6…118 %RH)</li>
 * </ul>
 */
public class HTU21DFMinimal {

    protected static final int CMD_T_HOLD     = 0xE3;
    protected static final int CMD_RH_HOLD    = 0xE5;
    protected static final int CMD_T_POLL     = 0xF3;
    protected static final int CMD_RH_POLL    = 0xF5;
    protected static final int CMD_WRITE_USER = 0xE6;
    protected static final int CMD_READ_USER  = 0xE7;
    protected static final int CMD_RESET      = 0xFE;
    protected static final int POLL_ATTEMPTS  = 100;

    protected final Connection connection;
    protected boolean hold = false;

    /**
     * Create the driver; does no bus access.
     *
     * @param connection I²C connection bound to 0x40
     */
    public HTU21DFMinimal(Connection connection) {
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
     * CRC-8 used by the HTU21D-F: polynomial 0x131 (x^8 + x^5 + x^4 + 1), init 0x00, no final XOR.
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
     * Wait 15 ms for power-up, soft reset (0xFE) and wait 15 ms.
     *
     * @throws IOException on bus error
     */
    public void init() throws IOException {
        sleepMs(15);
        command(CMD_RESET);
        sleepMs(15);
    }

    private byte[] measureOnce(int holdCmd, int pollCmd) throws IOException {
        if (hold) {
            command(holdCmd);
            return connection.read(3);
        }
        command(pollCmd);
        for (int i = 0; i < POLL_ATTEMPTS; i++) {
            try {
                return connection.read(3);
            } catch (IOException nack) {
                sleepMs(1);                       // read header NACKed: not done yet
            }
        }
        throw new IOException("HTU21D-F measurement did not complete");
    }

    /** Run one measurement and return the CRC-verified raw 16-bit code with the status bits cleared. */
    protected int measure(int holdCmd, int pollCmd) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            byte[] data = measureOnce(holdCmd, pollCmd);
            if (crc8(data, 0, 2) == (data[2] & 0xFF)) {
                return (((data[0] & 0xFF) << 8) | (data[1] & 0xFF)) & 0xFFFC;
            }
        }
        throw new IOException("HTU21D-F CRC mismatch");
    }

    protected static double toHumidity(int code) {
        return -6.0 + 125.0 * code / 65536.0;
    }

    protected static double toTemperature(int code) {
        return -46.85 + 175.72 * code / 65536.0;
    }

    /**
     * Measure temperature and humidity.
     *
     * @return {temperature in °C, relative humidity in %RH (not clamped)}
     * @throws IOException on bus error, timeout (100 ms) or two CRC mismatches
     */
    public double[] read() throws IOException {
        int t = measure(CMD_T_HOLD, CMD_T_POLL);
        int rh = measure(CMD_RH_HOLD, CMD_RH_POLL);
        return new double[]{toTemperature(t), toHumidity(rh)};
    }
}
