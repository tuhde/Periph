package it.uhde.periph.chips.gas;

import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * RRH62000 all-in-one air-quality module (Renesas) — minimal interface: one call returns
 * PM1/PM2.5/PM10 (KCl reference), temperature, humidity, TVOC, estimated CO₂ and the UBA IAQ index.
 *
 * <p>Over I²C (address {@code 0x69}, SEL pin to GND) the module is register addressed: a one-byte
 * command code is the register pointer; reading {@code 0x00} returns the 37-byte measurement block
 * with a CRC-8 (polynomial {@code 0x31}, init {@code 0xFF}).
 *
 * <p>The module must not be accessed more often than once per second (tRD); every method waits
 * for the access interval. The start-up time (1 s for I²C, 3.3 s for valid data) is counted from
 * construction, {@code wake()} and {@code softReset()}. The module defaults (10 moving averages,
 * fan 86 %, cleaning every 7 days for 10 s) are kept. The constructor does no bus access.
 */
public class Rrh62000Minimal {

    /** RRH62000 data error: CRC mismatch, start-up (all-0xFF) block or timeout. */
    public static class Rrh62000Exception extends IOException {
        /** @param message description */
        public Rrh62000Exception(String message) { super(message); }
    }

    /**
     * Main air-quality values of one sample.
     *
     * @param pm1UgM3      PM1, KCl reference, µg/m³
     * @param pm2_5UgM3    PM2.5, KCl reference, µg/m³
     * @param pm10UgM3     PM10, KCl reference, µg/m³
     * @param temperatureC temperature, °C
     * @param humidityRh   relative humidity, %RH
     * @param tvoc         TVOC, µg/m³ (LSB 10 µg/m³)
     * @param eco2Ppm      estimated CO₂, ppm (400–5000)
     * @param iaq          UBA indoor air quality index, 1.00–5.00
     */
    public record Measurement(double pm1UgM3, double pm2_5UgM3, double pm10UgM3, double temperatureC,
                              double humidityRh, int tvoc, int eco2Ppm, double iaq) {}

    /**
     * The decoded status word of the measurement block.
     *
     * @param raw               status word ({@code byte0 | byte1 << 8})
     * @param highConcentration bit 0: dust concentration extremely high (not measurable)
     * @param dustAccumulation  bit 1: dust accumulated inside the sensor (not measurable)
     * @param fanSpeed          bit 2: fan speed out of the set range
     * @param fan               bit 3: fan malfunction or breakdown
     */
    public record Status(int raw, boolean highConcentration, boolean dustAccumulation, boolean fanSpeed,
                         boolean fan) {}

    /**
     * Particle values of the measurement block.
     *
     * @param pm1UgM3        PM1, KCl reference, µg/m³
     * @param pm2_5UgM3      PM2.5, KCl reference, µg/m³
     * @param pm10UgM3       PM10, KCl reference, µg/m³
     * @param pm1SmokeUgM3   PM1, cigarette-smoke reference, µg/m³
     * @param pm2_5SmokeUgM3 PM2.5, cigarette-smoke reference, µg/m³
     * @param pm10SmokeUgM3  PM10, cigarette-smoke reference, µg/m³
     * @param nc0_3          particles 0.3–10 µm per cm³
     * @param nc0_5          particles 0.5–10 µm per cm³
     * @param nc1            particles 1–10 µm per cm³
     * @param nc2_5          particles 2.5–10 µm per cm³
     * @param nc4            particles 4–10 µm per cm³
     */
    public record Particles(double pm1UgM3, double pm2_5UgM3, double pm10UgM3, double pm1SmokeUgM3,
                            double pm2_5SmokeUgM3, double pm10SmokeUgM3, double nc0_3, double nc0_5,
                            double nc1, double nc2_5, double nc4) {}

    /**
     * The complete decoded measurement block.
     *
     * @param status       status word and flags
     * @param particles    PM values (both references) and number concentrations
     * @param temperatureC temperature, °C
     * @param humidityRh   relative humidity, %RH
     * @param tvoc         TVOC, µg/m³
     * @param eco2Ppm      estimated CO₂, ppm
     * @param iaq          UBA IAQ index, 1.00–5.00
     */
    public record Data(Status status, Particles particles, double temperatureC, double humidityRh, int tvoc,
                       int eco2Ppm, double iaq) {}

    /** Fixed 7-bit I²C address. */
    public static final int ADDRESS = 0x69;
    /** Length of the measurement block. */
    public static final int BLOCK_LENGTH = 37;

    protected static final int CMD_READ = 0x00;
    protected static final int CMD_DATA = 0x40;
    protected static final int CMD_SLEEP = 0x50;
    protected static final int CMD_CLEAN = 0x51;
    protected static final int CMD_RESET = 0x52;
    protected static final int CMD_MAVE = 0x53;
    protected static final int CMD_TINTC_H = 0x5A;
    protected static final int CMD_TINTC_L = 0x5B;
    protected static final int CMD_TCLEAN = 0x5C;
    protected static final int CMD_SPEEDFAN = 0x63;
    protected static final int CMD_RMOX = 0x71;
    protected static final int CMD_UID = 0x72;
    protected static final int CMD_ARGVER = 0x73;
    protected static final int CMD_CSTATUS = 0x74;
    protected static final int CMD_FWVER = 0x75;

    private static final long STARTUP_MS = 1000;
    private static final long VALID_MS = 3300;

    protected final RegisterConnection connection;
    private final long intervalMs;
    private long lastAccess;
    private long validAt;
    /** Timeout of the DATA polling of {@link #read()} in ms (default 4000). */
    protected long dataTimeoutMs = 4000;

    /**
     * Creates the driver with the 1 s access interval; no bus access.
     *
     * @param connection register connection (1-byte register addresses) bound to {@code 0x69}
     */
    public Rrh62000Minimal(RegisterConnection connection) {
        this(connection, 1000);
    }

    /**
     * Creates the driver; no bus access.
     *
     * @param connection       register connection (1-byte register addresses) bound to {@code 0x69}
     * @param accessIntervalMs minimum spacing between two accesses (the module needs ≥ 1000 ms;
     *                         lower it only for mocks)
     */
    public Rrh62000Minimal(RegisterConnection connection, long accessIntervalMs) {
        this.connection = connection;
        this.intervalMs = accessIntervalMs;
        markStartup();
    }

    private static long now() { return System.nanoTime() / 1_000_000L; }

    private static void sleepMs(long ms) throws IOException {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    /** Restarts the start-up timers (power-up, reset, wake-up); the waits scale with the interval. */
    protected final void markStartup() {
        long t = now();
        lastAccess = t + STARTUP_MS * intervalMs / 1000 - intervalMs;
        validAt = t + VALID_MS * intervalMs / 1000;
    }

    protected final byte[] readCmd(int cmd, int length) throws IOException {
        sleepMs(intervalMs - (now() - lastAccess));
        try {
            return connection.read(cmd, length);
        } finally {
            lastAccess = now();
        }
    }

    protected final int readByte(int cmd) throws IOException {
        return readCmd(cmd, 1)[0] & 0xFF;
    }

    protected final void writeCmd(int cmd, int value) throws IOException {
        sleepMs(intervalMs - (now() - lastAccess));
        try {
            connection.write(cmd, new byte[] {(byte) value});
        } finally {
            lastAccess = now();
        }
    }

    /**
     * CRC-8 of the measurement block (polynomial 0x31, init 0xFF; {@code crc8({0xBE, 0xEF}) == 0x92}).
     *
     * @param data   bytes
     * @param length number of bytes from the start of {@code data}
     * @return the CRC
     */
    public static int crc8(byte[] data, int length) {
        int crc = 0xFF;
        for (int i = 0; i < length; i++) {
            crc ^= data[i] & 0xFF;
            for (int b = 0; b < 8; b++) crc = (crc & 0x80) != 0 ? ((crc << 1) ^ 0x31) & 0xFF : (crc << 1) & 0xFF;
        }
        return crc;
    }

    /**
     * Decodes a 37-byte measurement block.
     *
     * @param b the raw block
     * @return the decoded values
     * @throws Rrh62000Exception on a wrong length, an all-0xFF block or a CRC mismatch
     */
    public static Data decode(byte[] b) throws Rrh62000Exception {
        if (b.length != BLOCK_LENGTH) throw new Rrh62000Exception("RRH62000 block must be 37 bytes");
        boolean allFF = true;
        for (int i = 0; i < 36; i++) if ((b[i] & 0xFF) != 0xFF) { allFF = false; break; }
        if (allFF) throw new Rrh62000Exception("RRH62000 sample invalid (start-up)");
        if (crc8(b, 36) != (b[36] & 0xFF)) throw new Rrh62000Exception("RRH62000 CRC error");
        int raw = (b[0] & 0xFF) | (b[1] & 0xFF) << 8;
        Status status = new Status(raw, (raw & 0x01) != 0, (raw & 0x02) != 0, (raw & 0x04) != 0, (raw & 0x08) != 0);
        Particles p = new Particles(w(b, 12) / 10.0, w(b, 14) / 10.0, w(b, 16) / 10.0, w(b, 18) / 10.0,
                w(b, 20) / 10.0, w(b, 22) / 10.0, w(b, 2) / 10.0, w(b, 4) / 10.0, w(b, 6) / 10.0,
                w(b, 8) / 10.0, w(b, 10) / 10.0);
        return new Data(status, p, (short) w(b, 24) / 100.0, w(b, 26) / 100.0, w(b, 28) * 10, w(b, 30),
                w(b, 32) / 100.0);
    }

    private static int w(byte[] b, int i) {
        return (b[i] & 0xFF) << 8 | (b[i + 1] & 0xFF);
    }

    protected final Data readBlock(boolean waitData) throws IOException {
        sleepMs(validAt - now());
        if (waitData) {
            long start = now();
            while (readByte(CMD_DATA) != 0x01) {
                if (now() - start >= dataTimeoutMs) throw new Rrh62000Exception("RRH62000 no new data within 4 s");
            }
        }
        return decode(readCmd(CMD_READ, BLOCK_LENGTH));
    }

    /**
     * Waits for the start-up time (1 s from construction) and reads the firmware version as a
     * presence check.
     *
     * @throws IOException on a bus error
     */
    public synchronized void init() throws IOException {
        readCmd(CMD_FWVER, 2);
    }

    /**
     * Waits for a new sample (DATA polled once per second, up to 4 s) and returns the main values.
     *
     * @return PM (KCl reference), temperature, humidity, TVOC, eCO₂ and IAQ
     * @throws Rrh62000Exception on a timeout, a CRC mismatch or a start-up block
     * @throws IOException       on a bus error
     */
    public synchronized Measurement read() throws IOException {
        Data d = readBlock(true);
        Particles p = d.particles();
        return new Measurement(p.pm1UgM3(), p.pm2_5UgM3(), p.pm10UgM3(), d.temperatureC(), d.humidityRh(),
                d.tvoc(), d.eco2Ppm(), d.iaq());
    }
}
