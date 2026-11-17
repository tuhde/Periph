package it.uhde.periph.chips.humidity;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * HDC1008 — low-power digital humidity and temperature sensor (Texas Instruments), minimal driver.
 *
 * <p>{@link #init()} writes the configuration 0x1000 (14-bit T and RH, sequence mode, heater off).
 * Each {@link #read()} triggers one sequence measurement (pointer 0x00), polls the NACKed read
 * header (timeout 50 ms) and converts the temperature and humidity words. I²C addresses 0x40–0x43.
 *
 * <h2>Configuration defaults</h2>
 * <ul>
 *   <li>Nothing is written at construction</li>
 *   <li>14-bit temperature and humidity, sequence mode (temperature first), heater off</li>
 * </ul>
 */
public class HDC1008Minimal {

    protected static final int REG_TEMPERATURE = 0x00;
    protected static final int REG_HUMIDITY    = 0x01;
    protected static final int REG_CONFIG      = 0x02;
    protected static final int REG_SERIAL1     = 0xFB;
    protected static final int CFG_RST  = 0x8000;
    protected static final int CFG_HEAT = 0x2000;
    protected static final int CFG_MODE = 0x1000;
    protected static final int CFG_BTST = 0x0800;
    protected static final int CFG_TRES = 0x0400;
    protected static final int CFG_HRES = 0x0300;
    protected static final int CONFIG_DEFAULT = 0x1000;
    protected static final int POLL_ATTEMPTS = 50;

    protected final Connection connection;
    protected int config = CONFIG_DEFAULT;
    protected byte[] pending = null;

    /**
     * Create the driver; does no bus access.
     *
     * @param connection plain I²C connection bound to 0x40–0x43
     */
    public HDC1008Minimal(Connection connection) {
        this.connection = connection;
    }

    protected static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    protected void writeConfig() throws IOException {
        connection.write(new byte[]{(byte) REG_CONFIG, (byte) (config >> 8), (byte) config});
    }

    /**
     * Write the default configuration 0x1000 (14-bit, sequence mode, heater off).
     *
     * @throws IOException on bus error
     */
    public void init() throws IOException {
        config = CONFIG_DEFAULT;
        pending = null;
        writeConfig();
    }

    protected byte[] triggerRead(int pointer, int n) throws IOException {
        pending = null;
        connection.write(new byte[]{(byte) pointer});
        for (int i = 0; i < POLL_ATTEMPTS; i++) {
            try {
                return connection.read(n);
            } catch (IOException nack) {
                sleepMs(1);                       // read header NACKed: not ready yet
            }
        }
        throw new IOException("HDC1008 measurement did not complete");
    }

    protected void setMode(boolean sequence) throws IOException {
        int want = sequence ? (config | CFG_MODE) : (config & ~CFG_MODE);
        if (want != config) {
            config = want;
            writeConfig();
        }
    }

    protected int[] measurePair() throws IOException {
        setMode(true);
        byte[] d = triggerRead(REG_TEMPERATURE, 4);
        return new int[]{((d[0] & 0xFF) << 8) | (d[1] & 0xFF), ((d[2] & 0xFF) << 8) | (d[3] & 0xFF)};
    }

    protected static double toTemperature(int raw) {
        return raw / 65536.0 * 165.0 - 40.0;
    }

    protected static double toHumidity(int raw) {
        return raw / 65536.0 * 100.0;
    }

    /**
     * Measure temperature and humidity (sequence mode, temperature first).
     *
     * @return {temperature in °C, relative humidity in %RH}
     * @throws IOException on bus error or timeout (50 ms)
     */
    public double[] read() throws IOException {
        int[] raw = measurePair();
        return new double[]{toTemperature(raw[0]), toHumidity(raw[1])};
    }
}
